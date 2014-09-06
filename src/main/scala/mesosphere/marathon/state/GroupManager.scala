package mesosphere.marathon.state

import java.net.URL
import javax.inject.Inject
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.collection.mutable

import akka.event.EventStream
import com.google.inject.Singleton
import com.google.inject.name.Named
import org.apache.log4j.Logger

import mesosphere.marathon.api.ModelValidation
import mesosphere.marathon.event.{ EventModule, GroupChangeFailed, GroupChangeSuccess }
import mesosphere.marathon.io.PathFun
import mesosphere.marathon.io.storage.StorageProvider
import mesosphere.marathon.tasks.TaskTracker
import mesosphere.marathon.upgrade._
import mesosphere.marathon.{ MarathonConf, MarathonSchedulerService, PortRangeExhaustedException }
import mesosphere.util.ThreadPoolContext.context

/**
  * The group manager is the facade for all group related actions.
  * It persists the state of a group and initiates deployments.
  */
class GroupManager @Singleton @Inject() (
    scheduler: MarathonSchedulerService,
    taskTracker: TaskTracker,
    groupRepo: GroupRepository,
    storage: StorageProvider,
    config: MarathonConf,
    @Named(EventModule.busName) eventBus: EventStream) extends ModelValidation with PathFun {

  private[this] val log = Logger.getLogger(getClass.getName)
  private[this] val zkName = "root"

  def root(withLatestApps: Boolean = true): Future[Group] = groupRepo.group(zkName, withLatestApps).map(_.getOrElse(Group.empty))

  /**
    * Get all available versions for given group identifier.
    * @param id the identifier of the group.
    * @return the list of versions of this object.
    */
  def versions(id: PathId): Future[Iterable[Timestamp]] = {
    groupRepo.listVersions(zkName).flatMap { versions =>
      Future.sequence(versions.map(groupRepo.group(zkName, _))).map {
        _.collect {
          case Some(group) if group.group(id).isDefined => group.version
        }
      }
    }
  }

  /**
    * Get a specific group by its id.
    * @param id the id of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId): Future[Option[Group]] = {
    root().map(_.findGroup(_.id == id))
  }

  /**
    * Get a specific group with a specific version.
    * @param id the identifier of the group.
    * @param version the version of the group.
    * @return the group if it is found, otherwise None
    */
  def group(id: PathId, version: Timestamp): Future[Option[Group]] = {
    groupRepo.group(zkName, version).map {
      _.flatMap(_.findGroup(_.id == id))
    }
  }

  /**
    * Update a group with given identifier.
    * The change of the group is defined by a change function.
    * The complete tree gets the given version.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param gid the id of the group to change.
    * @param version the new version of the group, after the change has applied.
    * @param fn the update function, which is applied to the group identified by given id
    * @param force only one update can be applied to applications at a time. with this flag
    *              one can control, to stop a current deployment and start a new one.
    * @return the deployment plan which will be executed.
    */
  def update(gid: PathId, fn: Group => Group, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[DeploymentPlan] = {
    upgrade(gid, _.update(gid, fn, version), version, force)
  }

  /**
    * Update application with given identifier and update function.
    * The change could take time to get deployed.
    * For this reason, we return the DeploymentPlan as result, which can be queried in the marathon scheduler.
    *
    * @param appId the identifier of the application
    * @param fn the application change function
    * @param version the version of the change
    * @param force if the change has to be forced.
    * @return the deployment plan which will be executed.
    */
  def updateApp(appId: PathId, fn: AppDefinition => AppDefinition, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[DeploymentPlan] = {
    upgrade(appId.parent, _.updateApp(appId, fn, version), version, force)
  }

  private def upgrade(gid: PathId, change: Group => Group, version: Timestamp = Timestamp.now(), force: Boolean = false): Future[DeploymentPlan] = synchronized {
    log.info(s"Upgrade id:$gid version:$version with force:$force")

    def deploy(from: Group, to: Group, resolve: Seq[ResolveArtifacts]): Future[DeploymentPlan] = {
      val plan = DeploymentPlan(from, to, resolve, version)
      scheduler.deploy(plan, force).map(_ => plan)
    }

    val rootGroup = root(withLatestApps = false)

    val deployment = for {
      from <- rootGroup //ignore the state of the scheduler
      (to, resolve) <- resolveStoreUrls(assignDynamicAppPort(from, change(from)))
      _ = requireValid(checkGroup(to))
      _ <- groupRepo.store(zkName, to)
      plan <- deploy(from, to, resolve)
    } yield plan

    deployment.onComplete {
      case Success(plan) =>
        log.info(s"Deployment acknowledged. Waiting to get processed: $plan")
        eventBus.publish(GroupChangeSuccess(gid, version.toString))
      case Failure(ex) =>
        log.warn(s"Deployment failed for change: $version", ex)
        eventBus.publish(GroupChangeFailed(gid, version.toString, ex.getMessage))
    }
    deployment
  }

  private[state] def resolveStoreUrls(group: Group): Future[(Group, Seq[ResolveArtifacts])] = {
    def url2Path(url: String) = contentPath(new URL(url)).map { url -> _ }
    Future.sequence(group.transitiveApps.flatMap(_.storeUrls).map(url2Path)).map(_.toMap).map { paths =>
      //Filter out all items with already existing path.
      //Since the path is derived from the content itself,
      //it will only change, if the content changes.
      val downloads = mutable.Map(paths.toSeq.filterNot{ case (url, path) => storage.item(path).exists }: _*)
      val actions = mutable.ListBuffer.empty[ResolveArtifacts]
      group.updateApp(group.version) { app =>
        if (app.storeUrls.isEmpty) app else {
          val storageUrls = app.storeUrls.map(paths).map(storage.item(_).url)
          val resolved = app.copy(uris = app.uris ++ storageUrls, storeUrls = Seq.empty)
          val appDownloads = app.storeUrls.flatMap(url => downloads.remove(url).map(path => new URL(url) -> path)).toMap
          if (appDownloads.nonEmpty) actions += ResolveArtifacts(resolved, appDownloads)
          resolved
        }
      } -> actions
    }
  }

  private[state] def assignDynamicAppPort(from: Group, to: Group): Group = {
    val portRange = Range(config.localPortMin(), config.localPortMax())
    var taken = from.transitiveApps.flatMap(_.ports)
    def nextGlobalFreePort: Integer = {
      val port = portRange.find(!taken.contains(_)).getOrElse(throw new PortRangeExhaustedException(config.localPortMin(), config.localPortMax()))
      log.info(s"Take next configured free port: $port")
      taken += port
      port
    }
    def appPorts(app: AppDefinition) = {
      val alreadyAssigned = mutable.Queue(from.app(app.id).map(_.ports).getOrElse(Nil).filter(portRange.contains): _*)
      def nextFreeAppPort = if (alreadyAssigned.nonEmpty) alreadyAssigned.dequeue() else nextGlobalFreePort
      val ports = app.ports.map { port => if (port == 0) nextFreeAppPort else port }
      app.copy(ports = ports)
    }
    val dynamicApps = to.transitiveApps.filter(_.hasDynamicPort).map(appPorts)
    dynamicApps.foldLeft(to) { (update, app) => update.updateApp(app.id, _ => app, app.version) }
  }
}

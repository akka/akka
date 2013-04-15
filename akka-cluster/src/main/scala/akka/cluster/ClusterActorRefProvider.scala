/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import akka.ConfigurationException
import akka.actor.ActorSystem
import akka.actor.ActorSystemImpl
import akka.actor.Deploy
import akka.actor.DynamicAccess
import akka.actor.InternalActorRef
import akka.actor.NoScopeGiven
import akka.actor.Scheduler
import akka.actor.Scope
import akka.actor.Terminated
import akka.dispatch.sysmsg.DeathWatchNotification
import akka.event.EventStream
import akka.japi.Util.immutableSeq
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteDeployer
import akka.remote.routing.RemoteRouterConfig
import akka.routing.RouterConfig
import akka.routing.DefaultResizer
import akka.cluster.routing.ClusterRouterConfig
import akka.cluster.routing.ClusterRouterSettings
import akka.cluster.routing.AdaptiveLoadBalancingRouter
import akka.cluster.routing.MixMetricsSelector
import akka.cluster.routing.HeapMetricsSelector
import akka.cluster.routing.SystemLoadAverageMetricsSelector
import akka.cluster.routing.CpuMetricsSelector
import akka.cluster.routing.MetricsSelector
import akka.dispatch.sysmsg.SystemMessage
import akka.actor.ActorRef
import akka.actor.Props

/**
 * INTERNAL API
 *
 * The `ClusterActorRefProvider` will load the [[akka.cluster.Cluster]]
 * extension, i.e. the cluster will automatically be started when
 * the `ClusterActorRefProvider` is used.
 */
private[akka] class ClusterActorRefProvider(
  _systemName: String,
  _settings: ActorSystem.Settings,
  _eventStream: EventStream,
  _dynamicAccess: DynamicAccess) extends RemoteActorRefProvider(
  _systemName, _settings, _eventStream, _dynamicAccess) {

  override def init(system: ActorSystemImpl): Unit = {
    super.init(system)

    // initialize/load the Cluster extension
    Cluster(system)
  }

  override protected def createRemoteWatcher(system: ActorSystemImpl): ActorRef = {
    // make sure Cluster extension is initialized/loaded from init thread
    Cluster(system)

    import remoteSettings._
    val failureDetector = createRemoteWatcherFailureDetector(system)
    system.systemActorOf(ClusterRemoteWatcher.props(
      failureDetector,
      heartbeatInterval = WatchHeartBeatInterval,
      unreachableReaperInterval = WatchUnreachableReaperInterval,
      heartbeatExpectedResponseAfter = WatchHeartbeatExpectedResponseAfter,
      numberOfEndHeartbeatRequests = WatchNumberOfEndHeartbeatRequests), "remote-watcher")
  }

  /**
   * Factory method to make it possible to override deployer in subclass
   * Creates a new instance every time
   */
  override protected def createDeployer: ClusterDeployer = new ClusterDeployer(settings, dynamicAccess)

}

/**
 * INTERNAL API
 *
 * Deployer of cluster aware routers.
 */
private[akka] class ClusterDeployer(_settings: ActorSystem.Settings, _pm: DynamicAccess) extends RemoteDeployer(_settings, _pm) {
  override def parseConfig(path: String, config: Config): Option[Deploy] = {
    super.parseConfig(path, config) match {
      case d @ Some(deploy) ⇒
        if (deploy.config.getBoolean("cluster.enabled")) {
          if (deploy.scope != NoScopeGiven)
            throw new ConfigurationException("Cluster deployment can't be combined with scope [%s]".format(deploy.scope))
          if (deploy.routerConfig.isInstanceOf[RemoteRouterConfig])
            throw new ConfigurationException("Cluster deployment can't be combined with [%s]".format(deploy.routerConfig))

          import ClusterRouterSettings.useRoleOption
          val clusterRouterSettings = ClusterRouterSettings(
            totalInstances = deploy.config.getInt("nr-of-instances"),
            maxInstancesPerNode = deploy.config.getInt("cluster.max-nr-of-instances-per-node"),
            allowLocalRoutees = deploy.config.getBoolean("cluster.allow-local-routees"),
            routeesPath = deploy.config.getString("cluster.routees-path"),
            useRole = useRoleOption(deploy.config.getString("cluster.use-role")))

          Some(deploy.copy(
            routerConfig = ClusterRouterConfig(deploy.routerConfig, clusterRouterSettings), scope = ClusterScope))
        } else d
      case None ⇒ None
    }
  }

  override protected def createRouterConfig(routerType: String, key: String, config: Config, deployment: Config): RouterConfig = {
    val routees = immutableSeq(deployment.getStringList("routees.paths"))
    val nrOfInstances = deployment.getInt("nr-of-instances")
    val resizer = if (config.hasPath("resizer")) Some(DefaultResizer(deployment.getConfig("resizer"))) else None

    routerType match {
      case "adaptive" ⇒
        val metricsSelector = deployment.getString("metrics-selector") match {
          case "mix"  ⇒ MixMetricsSelector
          case "heap" ⇒ HeapMetricsSelector
          case "cpu"  ⇒ CpuMetricsSelector
          case "load" ⇒ SystemLoadAverageMetricsSelector
          case fqn ⇒
            val args = List(classOf[Config] -> deployment)
            dynamicAccess.createInstanceFor[MetricsSelector](fqn, args).recover({
              case exception ⇒ throw new IllegalArgumentException(
                ("Cannot instantiate metrics-selector [%s], defined in [%s], " +
                  "make sure it extends [akka.cluster.routing.MetricsSelector] and " +
                  "has constructor with [com.typesafe.config.Config] parameter")
                  .format(fqn, key), exception)
            }).get
        }

        AdaptiveLoadBalancingRouter(metricsSelector, nrOfInstances, routees, resizer)

      case _ ⇒ super.createRouterConfig(routerType, key, config, deployment)
    }

  }
}

@SerialVersionUID(1L)
abstract class ClusterScope extends Scope

/**
 * Cluster aware scope of a [[akka.actor.Deploy]]
 */
case object ClusterScope extends ClusterScope {
  /**
   * Java API: get the singleton instance
   */
  def getInstance = this

  def withFallback(other: Scope): Scope = this
}

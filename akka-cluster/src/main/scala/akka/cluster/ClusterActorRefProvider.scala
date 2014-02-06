/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
import akka.cluster.routing.AdaptiveLoadBalancingRouter
import akka.cluster.routing.MixMetricsSelector
import akka.cluster.routing.HeapMetricsSelector
import akka.cluster.routing.SystemLoadAverageMetricsSelector
import akka.cluster.routing.CpuMetricsSelector
import akka.cluster.routing.MetricsSelector
import akka.dispatch.sysmsg.SystemMessage
import akka.actor.ActorRef
import akka.actor.Props
import akka.routing.Pool
import akka.routing.Group
import akka.cluster.routing.ClusterRouterPool
import akka.cluster.routing.ClusterRouterGroup
import com.typesafe.config.ConfigFactory
import akka.routing.DeprecatedRouterConfig
import akka.cluster.routing.ClusterRouterPoolSettings
import akka.cluster.routing.ClusterRouterGroupSettings

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
      heartbeatExpectedResponseAfter = WatchHeartbeatExpectedResponseAfter), "remote-watcher")
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

    // For backwards compatibility we must transform 'cluster.routees-path' to 'routees.paths'
    val config2 =
      if (config.hasPath("cluster.routees-path"))
        config.withFallback(ConfigFactory.parseString(s"""routees.paths=["${config.getString("cluster.routees-path")}"]"""))
      else config

    super.parseConfig(path, config2) match {
      case d @ Some(deploy) ⇒
        if (deploy.config.getBoolean("cluster.enabled")) {
          if (deploy.scope != NoScopeGiven)
            throw new ConfigurationException("Cluster deployment can't be combined with scope [%s]".format(deploy.scope))
          if (deploy.routerConfig.isInstanceOf[RemoteRouterConfig])
            throw new ConfigurationException("Cluster deployment can't be combined with [%s]".format(deploy.routerConfig))

          deploy.routerConfig match {
            case r: DeprecatedRouterConfig ⇒
              if (config.hasPath("cluster.routees-path"))
                Some(deploy.copy(
                  routerConfig = ClusterRouterGroup(r, ClusterRouterGroupSettings.fromConfig(deploy.config)), scope = ClusterScope))
              else
                Some(deploy.copy(
                  routerConfig = ClusterRouterPool(r, ClusterRouterPoolSettings.fromConfig(deploy.config)), scope = ClusterScope))
            case r: Pool ⇒
              Some(deploy.copy(
                routerConfig = ClusterRouterPool(r, ClusterRouterPoolSettings.fromConfig(deploy.config)), scope = ClusterScope))
            case r: Group ⇒
              Some(deploy.copy(
                routerConfig = ClusterRouterGroup(r, ClusterRouterGroupSettings.fromConfig(deploy.config)), scope = ClusterScope))
            case other ⇒
              throw new IllegalArgumentException(s"Cluster aware router can only wrap Pool or Group, got [${other.getClass.getName}]")
          }
        } else d
      case None ⇒ None
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

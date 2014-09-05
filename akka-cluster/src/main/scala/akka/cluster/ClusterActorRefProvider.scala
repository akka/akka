/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.ConfigurationException
import akka.actor.{ ActorRef, ActorSystem, ActorSystemImpl, Deploy, DynamicAccess, NoScopeGiven, Scope }
import akka.cluster.routing.{ ClusterRouterGroup, ClusterRouterGroupSettings, ClusterRouterPool, ClusterRouterPoolSettings }
import akka.event.EventStream
import akka.remote.{ RemoteActorRefProvider, RemoteDeployer }
import akka.remote.routing.RemoteRouterConfig
import akka.routing.{ Group, Pool }
import com.typesafe.config.Config

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

    super.parseConfig(path, config) match {
      case d @ Some(deploy) ⇒
        if (deploy.config.getBoolean("cluster.enabled")) {
          if (deploy.scope != NoScopeGiven)
            throw new ConfigurationException("Cluster deployment can't be combined with scope [%s]".format(deploy.scope))
          if (deploy.routerConfig.isInstanceOf[RemoteRouterConfig])
            throw new ConfigurationException("Cluster deployment can't be combined with [%s]".format(deploy.routerConfig))

          deploy.routerConfig match {
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

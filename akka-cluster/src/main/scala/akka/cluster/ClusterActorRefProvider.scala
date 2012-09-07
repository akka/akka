/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.Config
import akka.ConfigurationException
import akka.actor.ActorSystem
import akka.actor.Deploy
import akka.actor.DynamicAccess
import akka.actor.NoScopeGiven
import akka.actor.Scheduler
import akka.actor.Scope
import akka.cluster.routing.ClusterRouterConfig
import akka.event.EventStream
import akka.remote.RemoteActorRefProvider
import akka.remote.RemoteDeployer
import akka.routing.RemoteRouterConfig
import akka.cluster.routing.ClusterRouterSettings

class ClusterActorRefProvider(
  _systemName: String,
  _settings: ActorSystem.Settings,
  _eventStream: EventStream,
  _scheduler: Scheduler,
  _dynamicAccess: DynamicAccess) extends RemoteActorRefProvider(
  _systemName, _settings, _eventStream, _scheduler, _dynamicAccess) {

  override val deployer: ClusterDeployer = new ClusterDeployer(settings, dynamicAccess)

}

private[akka] class ClusterDeployer(_settings: ActorSystem.Settings, _pm: DynamicAccess) extends RemoteDeployer(_settings, _pm) {
  override def parseConfig(path: String, config: Config): Option[Deploy] = {
    super.parseConfig(path, config) match {
      case d @ Some(deploy) ⇒
        if (deploy.config.getBoolean("cluster.enabled")) {
          if (deploy.scope != NoScopeGiven)
            throw new ConfigurationException("Cluster deployment can't be combined with scope [%s]".format(deploy.scope))
          if (deploy.routerConfig.isInstanceOf[RemoteRouterConfig])
            throw new ConfigurationException("Cluster deployment can't be combined with [%s]".format(deploy.routerConfig))

          val clusterRouterSettings = ClusterRouterSettings(
            totalInstances = deploy.config.getInt("nr-of-instances"),
            maxInstancesPerNode = deploy.config.getInt("cluster.max-nr-of-instances-per-node"),
            deployOnOwnNode = deploy.config.getBoolean("cluster.deploy-on-own-node"))

          Some(deploy.copy(
            routerConfig = ClusterRouterConfig(deploy.routerConfig, clusterRouterSettings), scope = ClusterScope))
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

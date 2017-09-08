/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.NoSerializationVerificationNeeded
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.singleton.{ ClusterSingletonManagerSettings, ClusterSingletonProxy, ClusterSingletonProxySettings, ClusterSingletonManager ⇒ OldSingletonManager }
import akka.typed.internal.adapter.{ ActorRefAdapter, ActorSystemAdapter }
import akka.typed.scaladsl.adapter._
import akka.typed.{ ActorRef, ActorSystem, Behavior, Extension, ExtensionId, Props }
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

object ClusterSingletonSettings {
  def apply[A](
    system: ActorSystem[_]
  ): ClusterSingletonSettings = ???

  def fromConfig[A](
    config: Config
  ): ClusterSingletonSettings = ???
}

final class ClusterSingletonSettings(
  val role:                            Option[String],
  val dataCenter:                      Option[DataCenter],
  val singletonIdentificationInterval: FiniteDuration,
  val removalMargin:                   FiniteDuration,
  val handOverRetryInterval:           FiniteDuration,
  val bufferSize:                      Int) extends NoSerializationVerificationNeeded {

  private[akka] def toManagerSettings: ClusterSingletonManagerSettings = ???
  private[akka] def toProxySettings: ClusterSingletonProxySettings = ???

}

object ClusterSingletonManager extends ExtensionId[ClusterSingletonManager] {

  override def createExtension(system: ActorSystem[_]): ClusterSingletonManager =
    new ClusterSingletonManagerImpl(system)
}

private[akka] class ClusterSingletonManagerImpl(system: ActorSystem[_]) extends ClusterSingletonManager {
  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted actor systems can be used for the typed cluster singleton")

  private val untypedSystem = ActorSystemAdapter.toUntyped(system)

  override def spawn[A](
    behavior:           Behavior[A],
    name:               String,
    props:              Props,
    settings:           ClusterSingletonSettings,
    terminationMessage: Option[A]                = None) = {
    // just playing around a bit with how it could look here
    val adaptedProps = PropsAdapter(behavior, props)
    val managerName = s"singleton-manager-${name}"

    val cluster = Cluster(system)
    def shouldRunManager =
      (settings.role.isEmpty || cluster.self.roles(settings.role.get)) &&
        (settings.dataCenter.isEmpty || cluster.self.dataCenter == settings.dataCenter.get)

    if (shouldRunManager) {
      // start singleton on this node
      untypedSystem.actorOf(
        OldSingletonManager.props(adaptedProps, terminationMessage, settings.toManagerSettings),
        managerName)
    }

    // start proxy
    // Alternative idea, put a common supervisor for manager and proxy rather than using two places in the tree,
    // not sure what the gain would be though.
    val untypedProxy = untypedSystem.actorOf(
      ClusterSingletonProxy.props(s"/user/$managerName", settings.toProxySettings),
      s"singleton-proxy-${name}")

    ActorRefAdapter[A](untypedProxy)
  }
}

trait ClusterSingletonManager extends Extension {

  // Ideas:
  //  1. single method creates both manager and proxy
  //  2. method signature mimics the general actor starting signature and name
  // if needed we could also provide a spawnManager and a spawnProxy (but I think those are only ever used
  // to do the same kind of if-else-with-role/dc in user logic)
  /**
   * Start if needed and provide a proxy to a named singleton
   *
   * @return A proxy actor that can be used to communicate with the singleton in the cluster
   */
  def spawn[A](
    behavior:           Behavior[A],
    name:               String,
    props:              Props,
    settings:           ClusterSingletonSettings,
    terminationMessage: Option[A]                = None
  ): ActorRef[A]

}

/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.{ ExtendedActorSystem, InvalidActorNameException, NoSerializationVerificationNeeded }
import akka.annotation.{ DoNotInherit, InternalApi }
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
  ): ClusterSingletonSettings = {
    // TODO read existing cluster singleton settings or introduce its own config namespace?
    // currently singleton name is required and then discarded
    val mgrSettings = ClusterSingletonManagerSettings(system.settings.config.getConfig("akka.cluster.singleton"))
    val proxySettings = ClusterSingletonProxySettings(system.settings.config.getConfig("akka.cluster.singleton-proxy"))
    new ClusterSingletonSettings(
      mgrSettings.role,
      proxySettings.dataCenter,
      proxySettings.singletonIdentificationInterval,
      mgrSettings.removalMargin,
      mgrSettings.handOverRetryInterval,
      proxySettings.bufferSize
    )
  }

  def fromConfig[A](
    config: Config
  ): ClusterSingletonSettings = {
    ???
  }
}

final class ClusterSingletonSettings(
  val role:                            Option[String],
  val dataCenter:                      Option[DataCenter],
  val singletonIdentificationInterval: FiniteDuration,
  val removalMargin:                   FiniteDuration,
  val handOverRetryInterval:           FiniteDuration,
  val bufferSize:                      Int) extends NoSerializationVerificationNeeded {

  /**
   * Replace the required roles with a new set of required roles
   */
  def withRole(role: String): ClusterSingletonSettings =
    new ClusterSingletonSettings(Some(role), dataCenter, singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  def withNoRole(): ClusterSingletonSettings =
    new ClusterSingletonSettings(None, dataCenter, singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  def withDataCenter(dataCenter: DataCenter): ClusterSingletonSettings =
    new ClusterSingletonSettings(None, Some(dataCenter), singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  def withNoDataCenter(): ClusterSingletonSettings =
    new ClusterSingletonSettings(None, None, singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  def withRemovalMargin(removalMargin: FiniteDuration): ClusterSingletonSettings =
    new ClusterSingletonSettings(role, dataCenter, singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  def withHandoverRetryInterval(handOverRetryInterval: FiniteDuration): ClusterSingletonSettings =
    new ClusterSingletonSettings(role, dataCenter, singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  def withBufferSize(bufferSize: Int): ClusterSingletonSettings =
    new ClusterSingletonSettings(role, dataCenter, singletonIdentificationInterval, removalMargin, handOverRetryInterval, bufferSize)

  /**
   * INTERNAL API:
   */
  @InternalApi
  private[akka] def toManagerSettings(singletonName: String): ClusterSingletonManagerSettings =
    new ClusterSingletonManagerSettings(singletonName, role, removalMargin, handOverRetryInterval)

  /**
   * INTERNAL API:
   */
  @InternalApi
  private[akka] def toProxySettings(singletonName: String): ClusterSingletonProxySettings =
    new ClusterSingletonProxySettings(singletonName, role, singletonIdentificationInterval, bufferSize)

  /**
   * INTERNAL API:
   */
  @InternalApi
  private[akka] def shouldRunManager(cluster: Cluster): Boolean =
    (role.isEmpty || cluster.selfMember.roles(role.get)) &&
      (dataCenter.isEmpty || dataCenter.contains(cluster.selfMember.dataCenter))

}

object ClusterSingleton extends ExtensionId[ClusterSingleton] {

  override def createExtension(system: ActorSystem[_]): ClusterSingleton = new ClusterSingletonImpl(system)

}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] object ClusterSingletonImpl {
  def managerNameFor(singletonName: String) = s"singleton-manager-${singletonName}"
}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] final class ClusterSingletonImpl(system: ActorSystem[_]) extends ClusterSingleton {
  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted actor systems can be used for the typed cluster singleton")
  import ClusterSingletonImpl._

  private lazy val cluster = Cluster(system)
  private val untypedSystem = ActorSystemAdapter.toUntyped(system).asInstanceOf[ExtendedActorSystem]

  override def spawn[A](
    behavior:           Behavior[A],
    singletonName:      String,
    props:              Props,
    settings:           ClusterSingletonSettings,
    terminationMessage: A) = {

    spawnManager(behavior, singletonName, props, settings, terminationMessage)
    spawnProxy(singletonName, Props.empty, settings)
  }

  def spawnManager[A](behavior: Behavior[A], singletonName: String, props: Props, settings: ClusterSingletonSettings, terminationMessage: A): Boolean = {
    if (settings.shouldRunManager(cluster)) {
      val managerName = managerNameFor(singletonName)
      // start singleton on this node
      val adaptedProps = PropsAdapter(behavior, props)
      try {
        untypedSystem.systemActorOf(
          OldSingletonManager.props(adaptedProps, terminationMessage, settings.toManagerSettings(singletonName)),
          managerName)
      } catch {
        case ex: InvalidActorNameException ⇒
        // This is fine. We just wanted to make sure it is running and it already is
      }
      true
    } else
      false

  }

  def spawnProxy[A](singletonName: String, props: Props, settings: ClusterSingletonSettings): ActorRef[A] = {
    val proxyName = s"singleton-proxy-${singletonName}"
    val untypedProxy =
      try {
        untypedSystem.systemActorOf(
          ClusterSingletonProxy.props(s"/system/${managerNameFor(singletonName)}", settings.toProxySettings(singletonName)),
          proxyName)
      } catch {
        case ex: InvalidActorNameException ⇒
          // this is fine, we don't want to start more of it than one
          untypedSystem.actorFor(s"/system/$proxyName")
      }
    ActorRefAdapter[A](untypedProxy)
  }
}

/**
 * Not intended for user extension.
 */
@DoNotInherit
trait ClusterSingleton extends Extension {

  // Ideas:
  //  1. single method creates both manager and proxy
  //  2. method signature mimics the general actor starting signature and name
  // if needed we could also provide a spawnManager and a spawnProxy (but I think those are only ever used
  // to do the same kind of if-else-with-role/dc in user logic)

  // TODO what happens if there already is a singletonmgr for the name running?
  // TODO no way to start proxy wherever you want in hierarchy, is that a problem?
  /**
   * Start if needed and provide a proxy to a named singleton
   *
   * @param singletonName A cluster global unique name for this singleton
   * @return A proxy actor that can be used to communicate with the singleton in the cluster
   */
  def spawn[A](
    behavior:           Behavior[A],
    singletonName:      String,
    props:              Props,
    settings:           ClusterSingletonSettings,
    terminationMessage: A
  ): ActorRef[A]

  /**
   * Start only the singleton manager on this node if it fulfills the role and dc requirements in the settings.
   *
   * Prefer [[spawn]] unless you have a special reason to only start a manager.
   *
   * If there already is a manager running for the given singletonName on this node, no additional manager is started.
   *
   * @return true if the node did fulfill the requirements and was started or was already running, false if
   *         this node did not fulfill the requirements (and no manager was started)
   */
  def spawnManager[A](behavior: Behavior[A], singletonName: String, props: Props, settings: ClusterSingletonSettings, terminationMessage: A): Boolean

  /**
   * Start a singleton proxy for the given singleton name.
   *
   * Prefer [[spawn]] unless you have a special reason to only start a proxy
   *
   * If there already is a proxy running for the given singletonName on this node, an [[ActorRef]] to that is returned.
   */
  def spawnProxy[A](singletonName: String, props: Props, settings: ClusterSingletonSettings): ActorRef[A]

}

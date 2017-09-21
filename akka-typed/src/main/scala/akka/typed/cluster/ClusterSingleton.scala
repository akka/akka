/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.NoSerializationVerificationNeeded
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.singleton.{ ClusterSingletonManagerSettings, ClusterSingletonProxySettings }
import akka.typed.cluster.internal.AdaptedClusterSingletonImpl
import akka.typed.{ ActorRef, ActorSystem, Behavior, Extension, ExtensionId, Props }
import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration

object ClusterSingletonSettings {
  def apply(
    system: ActorSystem[_]
  ): ClusterSingletonSettings = fromConfig(system.settings.config.getConfig("akka.cluster"))

  def fromConfig(
    config: Config
  ): ClusterSingletonSettings = {
    // TODO introduce a config namespace for typed singleton and read that?
    // currently singleton name is required and then discarded, for example
    val mgrSettings = ClusterSingletonManagerSettings(config.getConfig("singleton"))
    val proxySettings = ClusterSingletonProxySettings(config.getConfig("singleton-proxy"))
    new ClusterSingletonSettings(
      mgrSettings.role,
      proxySettings.dataCenter,
      proxySettings.singletonIdentificationInterval,
      mgrSettings.removalMargin,
      mgrSettings.handOverRetryInterval,
      proxySettings.bufferSize
    )
  }
}

final class ClusterSingletonSettings(
  val role:                            Option[String],
  val dataCenter:                      Option[DataCenter],
  val singletonIdentificationInterval: FiniteDuration,
  val removalMargin:                   FiniteDuration,
  val handOverRetryInterval:           FiniteDuration,
  val bufferSize:                      Int) extends NoSerializationVerificationNeeded {

  def withRole(role: String): ClusterSingletonSettings = copy(role = Some(role))

  def withNoRole(): ClusterSingletonSettings = copy(role = None)

  def withDataCenter(dataCenter: DataCenter): ClusterSingletonSettings = copy(dataCenter = Some(dataCenter))

  def withNoDataCenter(): ClusterSingletonSettings = copy(dataCenter = None)

  def withRemovalMargin(removalMargin: FiniteDuration): ClusterSingletonSettings = copy(removalMargin = removalMargin)

  def withHandoverRetryInterval(handOverRetryInterval: FiniteDuration): ClusterSingletonSettings = copy(handOverRetryInterval = handOverRetryInterval)

  def withBufferSize(bufferSize: Int): ClusterSingletonSettings = copy(bufferSize = bufferSize)

  private def copy(
    role:                            Option[String]     = role,
    dataCenter:                      Option[DataCenter] = dataCenter,
    singletonIdentificationInterval: FiniteDuration     = singletonIdentificationInterval,
    removalMargin:                   FiniteDuration     = removalMargin,
    handOverRetryInterval:           FiniteDuration     = handOverRetryInterval,
    bufferSize:                      Int                = bufferSize) =
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

  override def createExtension(system: ActorSystem[_]): ClusterSingleton = new AdaptedClusterSingletonImpl(system)

  /**
   * Java API:
   */
  def get(system: ActorSystem[_]): ClusterSingleton = apply(system)
}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] object ClusterSingletonImpl {
  def managerNameFor(singletonName: String) = s"singletonManager${singletonName}"
}

/**
 * Not intended for user extension.
 */
@DoNotInherit
trait ClusterSingleton extends Extension {

  /**
   * Start if needed and provide a proxy to a named singleton
   *
   * If there already is a manager running for the given `singletonName` on this node, no additional manager is started.
   * If there already is a proxy running for the given `singletonName` on this node, an [[ActorRef]] to that is returned.
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

}

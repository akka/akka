/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.duration.FiniteDuration

import akka.actor._
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberJoined
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberWeaklyUp
import akka.remote.FailureDetectorRegistry
import akka.remote.RemoteWatcher
import akka.remote.RARP

/**
 * INTERNAL API
 */
private[cluster] object ClusterRemoteWatcher {

  /**
   * Factory method for `ClusterRemoteWatcher` [[akka.actor.Props]].
   */
  def props(
      failureDetector: FailureDetectorRegistry[Address],
      heartbeatInterval: FiniteDuration,
      unreachableReaperInterval: FiniteDuration,
      heartbeatExpectedResponseAfter: FiniteDuration): Props =
    Props(
      classOf[ClusterRemoteWatcher],
      failureDetector,
      heartbeatInterval,
      unreachableReaperInterval,
      heartbeatExpectedResponseAfter).withDeploy(Deploy.local)

  private final case class DelayedQuarantine(m: Member, previousStatus: MemberStatus)
      extends NoSerializationVerificationNeeded

}

/**
 * INTERNAL API
 *
 * Specialization of [[akka.remote.RemoteWatcher]] that keeps
 * track of cluster member nodes and is responsible for watchees on cluster nodes.
 * [[akka.actor.AddressTerminated]] is published when node is removed from cluster.
 *
 * `RemoteWatcher` handles non-cluster nodes. `ClusterRemoteWatcher` will take
 * over responsibility from `RemoteWatcher` if a watch is added before a node is member
 * of the cluster and then later becomes cluster member.
 */
private[cluster] class ClusterRemoteWatcher(
    failureDetector: FailureDetectorRegistry[Address],
    heartbeatInterval: FiniteDuration,
    unreachableReaperInterval: FiniteDuration,
    heartbeatExpectedResponseAfter: FiniteDuration)
    extends RemoteWatcher(failureDetector, heartbeatInterval, unreachableReaperInterval, heartbeatExpectedResponseAfter) {

  import ClusterRemoteWatcher.DelayedQuarantine

  private val arteryEnabled = RARP(context.system).provider.remoteSettings.Artery.Enabled
  val cluster = Cluster(context.system)
  import cluster.selfAddress

  private var pendingDelayedQuarantine: Set[UniqueAddress] = Set.empty

  var clusterNodes: Set[Address] = Set.empty

  override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
  }

  override def receive = receiveClusterEvent.orElse(super.receive)

  def receiveClusterEvent: Actor.Receive = {
    case state: CurrentClusterState =>
      clusterNodes = state.members.collect { case m if m.address != selfAddress => m.address }
      clusterNodes.foreach(takeOverResponsibility)
      unreachable = unreachable.diff(clusterNodes)
    case MemberJoined(m)                      => memberJoined(m)
    case MemberUp(m)                          => memberUp(m)
    case MemberWeaklyUp(m)                    => memberUp(m)
    case MemberRemoved(m, previousStatus)     => memberRemoved(m, previousStatus)
    case _: MemberEvent                       => // not interesting
    case DelayedQuarantine(m, previousStatus) => delayedQuarantine(m, previousStatus)
  }

  private def memberJoined(m: Member): Unit = {
    if (m.address != selfAddress)
      quarantineOldIncarnation(m)
  }

  def memberUp(m: Member): Unit =
    if (m.address != selfAddress) {
      quarantineOldIncarnation(m)
      clusterNodes += m.address
      takeOverResponsibility(m.address)
      unreachable -= m.address
    }

  def memberRemoved(m: Member, previousStatus: MemberStatus): Unit =
    if (m.address != selfAddress) {
      clusterNodes -= m.address

      if (previousStatus == MemberStatus.Down) {
        quarantine(
          m.address,
          Some(m.uniqueAddress.longUid),
          s"Cluster member removed, previous status [$previousStatus]",
          harmless = false)
      } else if (arteryEnabled) {
        // Don't quarantine gracefully removed members (leaving) directly,
        // give Cluster Singleton some time to exchange TakeOver/HandOver messages.
        // If new incarnation of same host:port is seen then the quarantine of previous incarnation
        // is triggered earlier.
        pendingDelayedQuarantine += m.uniqueAddress
        import context.dispatcher
        context.system.scheduler
          .scheduleOnce(cluster.settings.QuarantineRemovedNodeAfter, self, DelayedQuarantine(m, previousStatus))
      }

      publishAddressTerminated(m.address)
    }

  def quarantineOldIncarnation(newIncarnation: Member): Unit = {
    // If new incarnation of same host:port is seen then quarantine previous incarnation
    if (pendingDelayedQuarantine.nonEmpty)
      pendingDelayedQuarantine.find(_.address == newIncarnation.address).foreach { oldIncarnation =>
        pendingDelayedQuarantine -= oldIncarnation
        quarantine(
          oldIncarnation.address,
          Some(oldIncarnation.longUid),
          s"Cluster member removed, new incarnation joined",
          harmless = true)
      }
  }

  def delayedQuarantine(m: Member, previousStatus: MemberStatus): Unit = {
    if (pendingDelayedQuarantine(m.uniqueAddress)) {
      pendingDelayedQuarantine -= m.uniqueAddress
      quarantine(
        m.address,
        Some(m.uniqueAddress.longUid),
        s"Cluster member removed, previous status [$previousStatus]",
        harmless = true)
    }
  }

  override def watchNode(watchee: InternalActorRef): Unit =
    if (!clusterNodes(watchee.path.address)) super.watchNode(watchee)

  /**
   * When a cluster node is added this class takes over the
   * responsibility for watchees on that node already handled
   * by super RemoteWatcher.
   */
  def takeOverResponsibility(address: Address): Unit =
    if (watchingNodes(address)) {
      log.debug("Cluster is taking over responsibility of node: [{}]", address)
      unwatchNode(address)
    }

}

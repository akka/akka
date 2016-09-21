/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import scala.concurrent.duration.FiniteDuration
import akka.actor._
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
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
    failureDetector:                FailureDetectorRegistry[Address],
    heartbeatInterval:              FiniteDuration,
    unreachableReaperInterval:      FiniteDuration,
    heartbeatExpectedResponseAfter: FiniteDuration): Props =
    Props(classOf[ClusterRemoteWatcher], failureDetector, heartbeatInterval, unreachableReaperInterval,
      heartbeatExpectedResponseAfter).withDeploy(Deploy.local)
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
  failureDetector:                FailureDetectorRegistry[Address],
  heartbeatInterval:              FiniteDuration,
  unreachableReaperInterval:      FiniteDuration,
  heartbeatExpectedResponseAfter: FiniteDuration)
  extends RemoteWatcher(
    failureDetector,
    heartbeatInterval,
    unreachableReaperInterval,
    heartbeatExpectedResponseAfter) {

  val cluster = Cluster(context.system)
  import cluster.selfAddress

  var clusterNodes: Set[Address] = Set.empty

  override def preStart(): Unit = {
    super.preStart()
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    cluster.unsubscribe(self)
  }

  override def receive = receiveClusterEvent orElse super.receive

  def receiveClusterEvent: Actor.Receive = {
    case state: CurrentClusterState ⇒
      clusterNodes = state.members.collect { case m if m.address != selfAddress ⇒ m.address }
      clusterNodes foreach takeOverResponsibility
      unreachable = unreachable diff clusterNodes
    case MemberUp(m)                      ⇒ memberUp(m)
    case MemberWeaklyUp(m)                ⇒ memberUp(m)
    case MemberRemoved(m, previousStatus) ⇒ memberRemoved(m, previousStatus)
    case _: MemberEvent                   ⇒ // not interesting
  }

  def memberUp(m: Member): Unit =
    if (m.address != selfAddress) {
      clusterNodes += m.address
      takeOverResponsibility(m.address)
      unreachable -= m.address
    }

  def memberRemoved(m: Member, previousStatus: MemberStatus): Unit =
    if (m.address != selfAddress) {
      clusterNodes -= m.address
      // The reason we don't quarantine gracefully removed members (leaving) is that
      // Cluster Singleton need to exchange TakeOver/HandOver messages.
      if (previousStatus == MemberStatus.Down) {
        quarantine(m.address, Some(m.uniqueAddress.uid), s"Cluster member removed, previous status [$previousStatus]")
      }
      publishAddressTerminated(m.address)
    }

  override def watchNode(watchee: InternalActorRef) =
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

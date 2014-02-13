/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.actor.Address
import akka.actor.Props
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ClusterEvent.MemberRemoved
import akka.remote.FailureDetectorRegistry
import akka.remote.RemoteWatcher
import akka.actor.Deploy

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
    Props(classOf[ClusterRemoteWatcher], failureDetector, heartbeatInterval, unreachableReaperInterval,
      heartbeatExpectedResponseAfter).withDeploy(Deploy.local)
}

/**
 * INTERNAL API
 *
 * Specialization of [[akka.remote.RemoteWatcher]] that keeps
 * track of cluster member nodes and is responsible for watchees on cluster nodes.
 * [[akka.actor.AddressTerminate]] is published when node is removed from cluster.
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
  extends RemoteWatcher(
    failureDetector,
    heartbeatInterval,
    unreachableReaperInterval,
    heartbeatExpectedResponseAfter) {

  import RemoteWatcher._

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
    case WatchRemote(watchee, watcher) if clusterNodes(watchee.path.address) ⇒
      () // cluster managed node, don't propagate to super
    case state: CurrentClusterState ⇒
      clusterNodes = state.members.collect { case m if m.address != selfAddress ⇒ m.address }
      clusterNodes foreach takeOverResponsibility
      unreachable --= clusterNodes
    case MemberUp(m) ⇒
      if (m.address != selfAddress) {
        clusterNodes += m.address
        takeOverResponsibility(m.address)
        unreachable -= m.address
      }
    case MemberRemoved(m, previousStatus) ⇒
      if (m.address != selfAddress) {
        clusterNodes -= m.address
        if (previousStatus == MemberStatus.Down) {
          quarantine(m.address, Some(m.uniqueAddress.uid))
        }
        publishAddressTerminated(m.address)
      }
    case _: MemberEvent ⇒ // not interesting
  }

  /**
   * When a cluster node is added this class takes over the
   * responsibility for watchees on that node already handled
   * by super RemoteWatcher.
   */
  def takeOverResponsibility(address: Address): Unit = {
    watching foreach {
      case (watchee, watcher) ⇒ if (watchee.path.address == address)
        unwatchRemote(watchee, watcher)
    }
  }

}
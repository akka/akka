/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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

private[cluster] object ClusterRemoteWatcher {
  /**
   * Factory method for `ClusterRemoteWatcher` [[akka.actor.Props]].
   */
  def props(
    failureDetector: FailureDetectorRegistry[Address],
    heartbeatInterval: FiniteDuration,
    unreachableReaperInterval: FiniteDuration,
    heartbeatExpectedResponseAfter: FiniteDuration,
    numberOfEndHeartbeatRequests: Int): Props =
    Props(classOf[ClusterRemoteWatcher], failureDetector, heartbeatInterval, unreachableReaperInterval,
      heartbeatExpectedResponseAfter, numberOfEndHeartbeatRequests)
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
  heartbeatExpectedResponseAfter: FiniteDuration,
  numberOfEndHeartbeatRequests: Int)
  extends RemoteWatcher(
    failureDetector,
    heartbeatInterval,
    unreachableReaperInterval,
    heartbeatExpectedResponseAfter,
    numberOfEndHeartbeatRequests) {

  import RemoteWatcher._

  var clusterNodes: Set[Address] = Set.empty

  override def preStart(): Unit = {
    super.preStart()
    Cluster(context.system).subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    super.postStop()
    Cluster(context.system).unsubscribe(self)
  }

  override def receive = receiveClusterEvent orElse super.receive

  def receiveClusterEvent: Actor.Receive = {
    case WatchRemote(watchee, watcher) if clusterNodes(watchee.path.address) ⇒
      () // cluster managed node, don't propagate to super
    case state: CurrentClusterState ⇒
      clusterNodes = state.members.map(_.address)
      clusterNodes foreach takeOverResponsibility
    case MemberUp(m) ⇒
      clusterNodes += m.address
      takeOverResponsibility(m.address)
    case MemberRemoved(m) ⇒
      clusterNodes -= m.address
      publishAddressTerminated(m.address)
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
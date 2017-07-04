/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import akka.actor.{ Actor, ActorLogging, ActorRef, Address }
import akka.cluster.ClusterSettings.DataCenter
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.event.EventStream
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.actor.DeadLetterSuppression
import akka.annotation.InternalApi

import scala.collection.breakOut

/**
 * Domain events published to the event bus.
 * Subscribe with:
 * {{{
 *   Cluster(system).subscribe(actorRef, classOf[ClusterDomainEvent])
 * }}}
 */
object ClusterEvent {

  sealed abstract class SubscriptionInitialStateMode
  /**
   * When using this subscription mode a snapshot of
   * [[akka.cluster.ClusterEvent.CurrentClusterState]] will be sent to the
   * subscriber as the first message.
   */
  case object InitialStateAsSnapshot extends SubscriptionInitialStateMode
  /**
   * When using this subscription mode the events corresponding
   * to the current state will be sent to the subscriber to mimic what you would
   * have seen if you were listening to the events when they occurred in the past.
   */
  case object InitialStateAsEvents extends SubscriptionInitialStateMode

  /**
   * Java API
   */
  def initialStateAsSnapshot = InitialStateAsSnapshot

  /**
   * Java API
   */
  def initialStateAsEvents = InitialStateAsEvents

  /**
   * Marker interface for cluster domain events.
   */
  sealed trait ClusterDomainEvent extends DeadLetterSuppression

  /**
   * Current snapshot state of the cluster. Sent to new subscriber.
   *
   * @param leader leader of the data center of this node
   */
  final case class CurrentClusterState(
    members:       immutable.SortedSet[Member]  = immutable.SortedSet.empty,
    unreachable:   Set[Member]                  = Set.empty,
    seenBy:        Set[Address]                 = Set.empty,
    leader:        Option[Address]              = None,
    roleLeaderMap: Map[String, Option[Address]] = Map.empty) {

    /**
     * Java API: get current member list.
     */
    def getMembers: java.lang.Iterable[Member] = {
      import scala.collection.JavaConverters._
      members.asJava
    }

    /**
     * Java API: get current unreachable set.
     */
    def getUnreachable: java.util.Set[Member] =
      scala.collection.JavaConverters.setAsJavaSetConverter(unreachable).asJava

    /**
     * Java API: get current “seen-by” set.
     */
    def getSeenBy: java.util.Set[Address] =
      scala.collection.JavaConverters.setAsJavaSetConverter(seenBy).asJava

    /**
     * Java API: get address of current data center leader, or null if none
     */
    def getLeader: Address = leader orNull

    /**
     * get address of current leader, if any, within the data center that has the given role
     */
    def roleLeader(role: String): Option[Address] = roleLeaderMap.getOrElse(role, None)

    /**
     * Java API: get address of current leader, if any, within the data center that has the given role
     * or null if no such node exists
     */
    def getRoleLeader(role: String): Address = roleLeaderMap.get(role).flatten.orNull

    /**
     * All node roles in the cluster
     */
    def allRoles: Set[String] = roleLeaderMap.keySet

    /**
     * Java API: All node roles in the cluster
     */
    def getAllRoles: java.util.Set[String] =
      scala.collection.JavaConverters.setAsJavaSetConverter(allRoles).asJava

    /**
     * All data centers in the cluster
     */
    def allDataCenters: Set[String] = members.map(_.dataCenter)(breakOut)

    /**
     * Java API: All data centers in the cluster
     */
    def getAllDataCenters: java.util.Set[String] =
      scala.collection.JavaConverters.setAsJavaSetConverter(allDataCenters).asJava

  }

  /**
   * Marker interface for membership events.
   * Published when the state change is first seen on a node.
   * The state change was performed by the leader when there was
   * convergence on the leader node, i.e. all members had seen previous
   * state.
   */
  sealed trait MemberEvent extends ClusterDomainEvent {
    def member: Member
  }

  /**
   * Member status changed to Joining.
   */
  final case class MemberJoined(member: Member) extends MemberEvent {
    if (member.status != Joining) throw new IllegalArgumentException("Expected Joining status, got: " + member)
  }

  /**
   * Member status changed to WeaklyUp.
   * A joining member can be moved to `WeaklyUp` if convergence
   * cannot be reached, i.e. there are unreachable nodes.
   * It will be moved to `Up` when convergence is reached.
   */
  final case class MemberWeaklyUp(member: Member) extends MemberEvent {
    if (member.status != WeaklyUp) throw new IllegalArgumentException("Expected WeaklyUp status, got: " + member)
  }

  /**
   * Member status changed to Up.
   */
  final case class MemberUp(member: Member) extends MemberEvent {
    if (member.status != Up) throw new IllegalArgumentException("Expected Up status, got: " + member)
  }

  /**
   * Member status changed to Leaving.
   */
  final case class MemberLeft(member: Member) extends MemberEvent {
    if (member.status != Leaving) throw new IllegalArgumentException("Expected Leaving status, got: " + member)
  }

  /**
   * Member status changed to `MemberStatus.Exiting` and will be removed
   * when all members have seen the `Exiting` status.
   */
  final case class MemberExited(member: Member) extends MemberEvent {
    if (member.status != Exiting) throw new IllegalArgumentException("Expected Exiting status, got: " + member)
  }

  /**
   * Member completely removed from the cluster.
   * When `previousStatus` is `MemberStatus.Down` the node was removed
   * after being detected as unreachable and downed.
   * When `previousStatus` is `MemberStatus.Exiting` the node was removed
   * after graceful leaving and exiting.
   */
  final case class MemberRemoved(member: Member, previousStatus: MemberStatus) extends MemberEvent {
    if (member.status != Removed) throw new IllegalArgumentException("Expected Removed status, got: " + member)
  }

  /**
   * Leader of the cluster data center of this node changed. Published when the state change
   * is first seen on a node.
   */
  final case class LeaderChanged(leader: Option[Address]) extends ClusterDomainEvent {
    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * First member (leader) of the members within a role set (in the same data center as this node,
   * if data centers are used) changed.
   * Published when the state change is first seen on a node.
   */
  final case class RoleLeaderChanged(role: String, leader: Option[Address]) extends ClusterDomainEvent {
    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * This event is published when the cluster node is shutting down,
   * before the final [[MemberRemoved]] events are published.
   */
  final case object ClusterShuttingDown extends ClusterDomainEvent

  /**
   * Java API: get the singleton instance of `ClusterShuttingDown` event
   */
  def getClusterShuttingDownInstance = ClusterShuttingDown

  /**
   * Marker interface to facilitate subscription of
   * both [[UnreachableMember]] and [[ReachableMember]].
   */
  sealed trait ReachabilityEvent extends ClusterDomainEvent {
    def member: Member
  }

  /**
   * A member is considered as unreachable by the failure detector.
   */
  final case class UnreachableMember(member: Member) extends ReachabilityEvent

  /**
   * A member is considered as reachable by the failure detector
   * after having been unreachable.
   * @see [[UnreachableMember]]
   */
  final case class ReachableMember(member: Member) extends ReachabilityEvent

  /**
   * INTERNAL API
   * The nodes that have seen current version of the Gossip.
   */
  private[cluster] final case class SeenChanged(convergence: Boolean, seenBy: Set[Address]) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] final case class ReachabilityChanged(reachability: Reachability) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] final case class CurrentInternalStats(
    gossipStats: GossipStats,
    vclockStats: VectorClockStats) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] def diffUnreachable(oldState: MembershipState, newState: MembershipState): immutable.Seq[UnreachableMember] =
    if (newState eq oldState) Nil
    else {
      val oldGossip = oldState.latestGossip
      val newGossip = newState.latestGossip
      val oldUnreachableNodes = oldGossip.overview.reachability.allUnreachableOrTerminated
      (newGossip.overview.reachability.allUnreachableOrTerminated.collect {
        case node if !oldUnreachableNodes.contains(node) && node != newState.selfUniqueAddress ⇒
          UnreachableMember(newGossip.member(node))
      })(collection.breakOut)
    }

  /**
   * INTERNAL API
   */
  private[cluster] def diffReachable(oldState: MembershipState, newState: MembershipState): immutable.Seq[ReachableMember] =
    if (newState eq oldState) Nil
    else {
      val oldGossip = oldState.latestGossip
      val newGossip = newState.latestGossip
      (oldState.overview.reachability.allUnreachable.collect {
        case node if newGossip.hasMember(node) && newGossip.overview.reachability.isReachable(node) && node != newState.selfUniqueAddress ⇒
          ReachableMember(newGossip.member(node))
      })(collection.breakOut)

    }

  /**
   * INTERNAL API.
   */
  private[cluster] def diffMemberEvents(oldState: MembershipState, newState: MembershipState): immutable.Seq[MemberEvent] =
    if (newState eq oldState) Nil
    else {
      val oldGossip = oldState.latestGossip
      val newGossip = newState.latestGossip
      val newMembers = newGossip.members diff oldGossip.members
      val membersGroupedByAddress = List(newGossip.members, oldGossip.members).flatten.groupBy(_.uniqueAddress)
      val changedMembers = membersGroupedByAddress collect {
        case (_, newMember :: oldMember :: Nil) if newMember.status != oldMember.status || newMember.upNumber != oldMember.upNumber ⇒
          newMember
      }
      val memberEvents = (newMembers ++ changedMembers) collect {
        case m if m.status == Joining  ⇒ MemberJoined(m)
        case m if m.status == WeaklyUp ⇒ MemberWeaklyUp(m)
        case m if m.status == Up       ⇒ MemberUp(m)
        case m if m.status == Leaving  ⇒ MemberLeft(m)
        case m if m.status == Exiting  ⇒ MemberExited(m)
        // no events for other transitions
      }

      val removedMembers = oldGossip.members diff newGossip.members
      val removedEvents = removedMembers.map(m ⇒ MemberRemoved(m.copy(status = Removed), m.status))

      (new VectorBuilder[MemberEvent]() ++= memberEvents ++= removedEvents).result()
    }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[cluster] def diffLeader(oldState: MembershipState, newState: MembershipState): immutable.Seq[LeaderChanged] = {
    val newLeader = newState.leader
    if (newLeader != oldState.leader) List(LeaderChanged(newLeader.map(_.address)))
    else Nil
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[cluster] def diffRolesLeader(oldState: MembershipState, newState: MembershipState): Set[RoleLeaderChanged] = {
    for {
      role ← oldState.latestGossip.allRoles union newState.latestGossip.allRoles
      newLeader = newState.roleLeader(role)
      if newLeader != oldState.roleLeader(role)
    } yield RoleLeaderChanged(role, newLeader.map(_.address))
  }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[cluster] def diffSeen(oldState: MembershipState, newState: MembershipState): immutable.Seq[SeenChanged] =
    if (oldState eq newState) Nil
    else {
      val newConvergence = newState.convergence(Set.empty)
      val newSeenBy = newState.latestGossip.seenBy
      if (newConvergence != oldState.convergence(Set.empty) || newSeenBy != oldState.latestGossip.seenBy)
        List(SeenChanged(newConvergence, newSeenBy.map(_.address)))
      else Nil
    }

  /**
   * INTERNAL API
   */
  @InternalApi
  private[cluster] def diffReachability(oldState: MembershipState, newState: MembershipState): immutable.Seq[ReachabilityChanged] =
    if (newState.overview.reachability eq oldState.overview.reachability) Nil
    else List(ReachabilityChanged(newState.overview.reachability))

}

/**
 * INTERNAL API.
 * Responsible for domain event subscriptions and publishing of
 * domain events to event bus.
 */
private[cluster] final class ClusterDomainEventPublisher extends Actor with ActorLogging
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] {
  import InternalClusterAction._

  val cluster = Cluster(context.system)
  val selfUniqueAddress = cluster.selfUniqueAddress
  val emptyMembershipState = MembershipState(Gossip.empty, cluster.selfUniqueAddress, cluster.settings.DataCenter)
  var membershipState: MembershipState = emptyMembershipState
  def selfDc = cluster.settings.DataCenter

  override def preRestart(reason: Throwable, message: Option[Any]) {
    // don't postStop when restarted, no children to stop
  }

  override def postStop(): Unit = {
    // publish the final removed state before shutting down
    publish(ClusterShuttingDown)
    publishChanges(emptyMembershipState)
  }

  def receive = {
    case PublishChanges(newState)            ⇒ publishChanges(newState)
    case currentStats: CurrentInternalStats  ⇒ publishInternalStats(currentStats)
    case SendCurrentClusterState(receiver)   ⇒ sendCurrentClusterState(receiver)
    case Subscribe(subscriber, initMode, to) ⇒ subscribe(subscriber, initMode, to)
    case Unsubscribe(subscriber, to)         ⇒ unsubscribe(subscriber, to)
    case PublishEvent(event)                 ⇒ publish(event)
  }

  def eventStream: EventStream = context.system.eventStream

  /**
   * The current snapshot state corresponding to latest gossip
   * to mimic what you would have seen if you were listening to the events.
   */
  def sendCurrentClusterState(receiver: ActorRef): Unit = {
    val unreachable: Set[Member] =
      membershipState.latestGossip.overview.reachability.allUnreachableOrTerminated.collect {
        case node if node != selfUniqueAddress ⇒ membershipState.latestGossip.member(node)
      }
    val state = CurrentClusterState(
      members = membershipState.latestGossip.members,
      unreachable = unreachable,
      seenBy = membershipState.latestGossip.seenBy.map(_.address),
      leader = membershipState.leader.map(_.address),
      roleLeaderMap = membershipState.latestGossip.allRoles.map(r ⇒
        r → membershipState.roleLeader(r).map(_.address))(collection.breakOut))
    receiver ! state
  }

  def subscribe(subscriber: ActorRef, initMode: SubscriptionInitialStateMode, to: Set[Class[_]]): Unit = {
    initMode match {
      case InitialStateAsEvents ⇒
        def pub(event: AnyRef): Unit = {
          if (to.exists(_.isAssignableFrom(event.getClass)))
            subscriber ! event
        }
        publishDiff(emptyMembershipState, membershipState, pub)
      case InitialStateAsSnapshot ⇒
        sendCurrentClusterState(subscriber)
    }

    to foreach { eventStream.subscribe(subscriber, _) }
  }

  def unsubscribe(subscriber: ActorRef, to: Option[Class[_]]): Unit = to match {
    case None    ⇒ eventStream.unsubscribe(subscriber)
    case Some(c) ⇒ eventStream.unsubscribe(subscriber, c)
  }

  def publishChanges(newState: MembershipState): Unit = {
    val oldState = membershipState
    // keep the latest state to be sent to new subscribers
    membershipState = newState
    publishDiff(oldState, newState, publish)
  }

  def publishDiff(oldState: MembershipState, newState: MembershipState, pub: AnyRef ⇒ Unit): Unit = {
    diffMemberEvents(oldState, newState) foreach pub
    diffUnreachable(oldState, newState) foreach pub
    diffReachable(oldState, newState) foreach pub
    diffLeader(oldState, newState) foreach pub
    diffRolesLeader(oldState, newState) foreach pub
    // publish internal SeenState for testing purposes
    diffSeen(oldState, newState) foreach pub
    diffReachability(oldState, newState) foreach pub
  }

  def publishInternalStats(currentStats: CurrentInternalStats): Unit = publish(currentStats)

  def publish(event: AnyRef): Unit = eventStream publish event

  def clearState(): Unit = {
    membershipState = emptyMembershipState
  }
}

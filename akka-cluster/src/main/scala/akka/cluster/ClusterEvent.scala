/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable
import scala.collection.immutable.VectorBuilder
import akka.actor.{ Actor, ActorLogging, ActorRef, Address }
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.event.EventStream
import akka.actor.AddressTerminated

/**
 * Domain events published to the event bus.
 * Subscribe with:
 * {{{
 *   Cluster(system).subscribe(actorRef, classOf[ClusterDomainEvent])
 * }}}
 */
object ClusterEvent {
  /**
   * Marker interface for cluster domain events.
   */
  sealed trait ClusterDomainEvent

  /**
   * Current snapshot state of the cluster. Sent to new subscriber.
   */
  case class CurrentClusterState(
    members: immutable.SortedSet[Member] = immutable.SortedSet.empty,
    unreachable: Set[Member] = Set.empty,
    seenBy: Set[Address] = Set.empty,
    leader: Option[Address] = None,
    roleLeaderMap: Map[String, Option[Address]] = Map.empty) extends ClusterDomainEvent {

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
     * Java API: get address of current leader, or null if none
     */
    def getLeader: Address = leader orNull

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
     * get address of current leader, if any, within the role set
     */
    def roleLeader(role: String): Option[Address] = roleLeaderMap.getOrElse(role, None)

    /**
     * Java API: get address of current leader within the role set,
     * or null if no node with that role
     */
    def getRoleLeader(role: String): Address = roleLeaderMap.get(role).flatten.orNull
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
   * Member status changed to Up.
   */
  case class MemberUp(member: Member) extends MemberEvent {
    if (member.status != Up) throw new IllegalArgumentException("Expected Up status, got: " + member)
  }

  /**
   * Member status changed to Exiting.
   */
  case class MemberExited(member: Member) extends MemberEvent {
    if (member.status != Exiting) throw new IllegalArgumentException("Expected Exiting status, got: " + member)
  }

  /**
   * Member completely removed from the cluster.
   */
  case class MemberRemoved(member: Member) extends MemberEvent {
    if (member.status != Removed) throw new IllegalArgumentException("Expected Removed status, got: " + member)
  }

  /**
   * Leader of the cluster members changed. Published when the state change
   * is first seen on a node.
   */
  case class LeaderChanged(leader: Option[Address]) extends ClusterDomainEvent {
    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * First member (leader) of the members within a role set changed.
   * Published when the state change is first seen on a node.
   */
  case class RoleLeaderChanged(role: String, leader: Option[Address]) extends ClusterDomainEvent {
    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * A member is considered as unreachable by the failure detector.
   */
  case class UnreachableMember(member: Member) extends ClusterDomainEvent

  /**
   * INTERNAL API
   *
   * Current snapshot of cluster node metrics. Published to subscribers.
   */
  case class ClusterMetricsChanged(nodeMetrics: Set[NodeMetrics]) extends ClusterDomainEvent {
    /**
     * Java API
     */
    def getNodeMetrics: java.lang.Iterable[NodeMetrics] =
      scala.collection.JavaConverters.asJavaIterableConverter(nodeMetrics).asJava
  }

  /**
   * INTERNAL API
   * The nodes that have seen current version of the Gossip.
   */
  private[cluster] case class SeenChanged(convergence: Boolean, seenBy: Set[Address]) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] case class CurrentInternalStats(stats: ClusterStats) extends ClusterDomainEvent

  /**
   * INTERNAL API
   */
  private[cluster] def diffUnreachable(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[UnreachableMember] =
    if (newGossip eq oldGossip) Nil
    else {
      val newUnreachable = newGossip.overview.unreachable -- oldGossip.overview.unreachable
      val unreachableEvents = newUnreachable map UnreachableMember

      immutable.Seq.empty ++ unreachableEvents
    }

  /**
   * INTERNAL API.
   */
  private[cluster] def diffMemberEvents(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[MemberEvent] =
    if (newGossip eq oldGossip) Nil
    else {
      val newMembers = newGossip.members -- oldGossip.members
      val membersGroupedByAddress = List(newGossip.members, oldGossip.members).flatten.groupBy(_.uniqueAddress)
      val changedMembers = membersGroupedByAddress collect {
        case (_, newMember :: oldMember :: Nil) if newMember.status != oldMember.status ⇒ newMember
      }
      val memberEvents = (newMembers ++ changedMembers) collect {
        case m if m.status == Up      ⇒ MemberUp(m)
        case m if m.status == Exiting ⇒ MemberExited(m)
        // no events for other transitions
      }

      val allNewUnreachable = newGossip.overview.unreachable -- oldGossip.overview.unreachable

      val unreachableGroupedByAddress =
        List(newGossip.overview.unreachable, oldGossip.overview.unreachable).flatten.groupBy(_.uniqueAddress)
      val unreachableDownMembers = unreachableGroupedByAddress collect {
        case (_, newMember :: oldMember :: Nil) if newMember.status == Down && newMember.status != oldMember.status ⇒
          newMember
      }

      val removedMembers = (oldGossip.members -- newGossip.members -- newGossip.overview.unreachable) ++
        (oldGossip.overview.unreachable -- newGossip.overview.unreachable)
      val removedEvents = removedMembers.map(m ⇒ MemberRemoved(m.copy(status = Removed)))

      (new VectorBuilder[MemberEvent]() ++= memberEvents ++= removedEvents).result()
    }

  /**
   * INTERNAL API
   */
  private[cluster] def diffLeader(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[LeaderChanged] = {
    val newLeader = newGossip.leader
    if (newLeader != oldGossip.leader) List(LeaderChanged(newLeader.map(_.address)))
    else Nil
  }

  /**
   * INTERNAL API
   */
  private[cluster] def diffRolesLeader(oldGossip: Gossip, newGossip: Gossip): Set[RoleLeaderChanged] = {
    for {
      role ← (oldGossip.allRoles ++ newGossip.allRoles)
      newLeader = newGossip.roleLeader(role)
      if newLeader != oldGossip.roleLeader(role)
    } yield RoleLeaderChanged(role, newLeader.map(_.address))
  }

  /**
   * INTERNAL API
   */
  private[cluster] def diffSeen(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[SeenChanged] =
    if (newGossip eq oldGossip) Nil
    else {
      val newConvergence = newGossip.convergence
      val newSeenBy = newGossip.seenBy
      if (newConvergence != oldGossip.convergence || newSeenBy != oldGossip.seenBy)
        List(SeenChanged(newConvergence, newSeenBy.map(_.address)))
      else Nil
    }
}

/**
 * INTERNAL API.
 * Responsible for domain event subscriptions and publishing of
 * domain events to event bus.
 */
private[cluster] final class ClusterDomainEventPublisher extends Actor with ActorLogging {
  import InternalClusterAction._

  var latestGossip: Gossip = Gossip.empty

  override def preRestart(reason: Throwable, message: Option[Any]) {
    // don't postStop when restarted, no children to stop
  }

  override def postStop(): Unit = {
    // publish the final removed state before shutting down
    publishChanges(Gossip.empty)
  }

  def receive = {
    case PublishChanges(newGossip)            ⇒ publishChanges(newGossip)
    case currentStats: CurrentInternalStats   ⇒ publishInternalStats(currentStats)
    case PublishCurrentClusterState(receiver) ⇒ publishCurrentClusterState(receiver)
    case Subscribe(subscriber, to)            ⇒ subscribe(subscriber, to)
    case Unsubscribe(subscriber, to)          ⇒ unsubscribe(subscriber, to)
    case PublishEvent(event)                  ⇒ publish(event)
  }

  def eventStream: EventStream = context.system.eventStream

  /**
   * The current snapshot state corresponding to latest gossip
   * to mimic what you would have seen if you where listening to the events.
   */
  def publishCurrentClusterState(receiver: Option[ActorRef]): Unit = {
    val state = CurrentClusterState(
      members = latestGossip.members,
      unreachable = latestGossip.overview.unreachable,
      seenBy = latestGossip.seenBy.map(_.address),
      leader = latestGossip.leader.map(_.address),
      roleLeaderMap = latestGossip.allRoles.map(r ⇒ r -> latestGossip.roleLeader(r).map(_.address))(collection.breakOut))
    receiver match {
      case Some(ref) ⇒ ref ! state
      case None      ⇒ publish(state)
    }
  }

  def subscribe(subscriber: ActorRef, to: Class[_]): Unit = {
    publishCurrentClusterState(Some(subscriber))
    eventStream.subscribe(subscriber, to)
  }

  def unsubscribe(subscriber: ActorRef, to: Option[Class[_]]): Unit = to match {
    case None    ⇒ eventStream.unsubscribe(subscriber)
    case Some(c) ⇒ eventStream.unsubscribe(subscriber, c)
  }

  def publishChanges(newGossip: Gossip): Unit = {
    val oldGossip = latestGossip
    // keep the latestGossip to be sent to new subscribers
    latestGossip = newGossip
    // first publish the diffUnreachable between the last two gossips
    diffUnreachable(oldGossip, newGossip) foreach publish
    diffMemberEvents(oldGossip, newGossip) foreach { event ⇒
      event match {
        case MemberRemoved(m) ⇒
          publish(event)
          // notify DeathWatch about downed node
          publish(AddressTerminated(m.address))
        case _ ⇒ publish(event)
      }
    }
    diffLeader(oldGossip, newGossip) foreach publish
    diffRolesLeader(oldGossip, newGossip) foreach publish
    // publish internal SeenState for testing purposes
    diffSeen(oldGossip, newGossip) foreach publish
  }

  def publishInternalStats(currentStats: CurrentInternalStats): Unit = publish(currentStats)

  def publish(event: AnyRef): Unit = eventStream publish event

  def publishStart(): Unit =
    if (latestGossip ne Gossip.empty) {
      clearState()
      publishCurrentClusterState(None)
    }

  def clearState(): Unit = {
    latestGossip = Gossip.empty
  }
}

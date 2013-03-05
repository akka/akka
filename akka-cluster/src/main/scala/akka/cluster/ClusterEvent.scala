/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable
import scala.collection.immutable.{ VectorBuilder, SortedSet }
import akka.actor.{ Actor, ActorLogging, ActorRef, Address }
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._
import akka.event.EventStream
import akka.actor.AddressTerminated
import java.lang.Iterable
import akka.japi.Util.immutableSeq
import akka.util.Collections.EmptyImmutableSeq

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
    leader: Option[Address] = None) extends ClusterDomainEvent {

    /**
     * Java API
     * Read only
     */
    def getMembers: java.lang.Iterable[Member] = {
      import scala.collection.JavaConverters._
      members.asJava
    }

    /**
     * Java API
     * Read only
     */
    def getUnreachable: java.util.Set[Member] =
      scala.collection.JavaConverters.setAsJavaSetConverter(unreachable).asJava

    /**
     * Java API
     * Read only
     */
    def getSeenBy: java.util.Set[Address] =
      scala.collection.JavaConverters.setAsJavaSetConverter(seenBy).asJava

    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
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
   * Leader of the cluster members changed. Only published after convergence.
   */
  case class LeaderChanged(leader: Option[Address]) extends ClusterDomainEvent {
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
      val membersGroupedByAddress = List(newGossip.members, oldGossip.members).flatten.groupBy(_.address)
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
        List(newGossip.overview.unreachable, oldGossip.overview.unreachable).flatten.groupBy(_.address)
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
  private[cluster] def diffLeader(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[LeaderChanged] =
    if (newGossip.leader != oldGossip.leader) List(LeaderChanged(newGossip.leader))
    else Nil

  /**
   * INTERNAL API
   */
  private[cluster] def diffSeen(oldGossip: Gossip, newGossip: Gossip): immutable.Seq[SeenChanged] =
    if (newGossip eq oldGossip) Nil
    else {
      val newConvergence = newGossip.convergence
      val newSeenBy = newGossip.seenBy
      if (newConvergence != oldGossip.convergence || newSeenBy != oldGossip.seenBy)
        List(SeenChanged(newConvergence, newSeenBy))
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
  var latestConvergedGossip: Gossip = Gossip.empty
  var bufferedEvents: immutable.IndexedSeq[ClusterDomainEvent] = Vector.empty

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
    case PublishStart                         ⇒ publishStart()
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
      seenBy = latestGossip.seenBy,
      leader = latestConvergedGossip.leader)
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
    // buffer up the LeaderChanged waiting for convergence
    bufferedEvents ++= diffLeader(oldGossip, newGossip)
    // if we have convergence then publish the MemberEvents and LeaderChanged
    if (newGossip.convergence) {
      val previousConvergedGossip = latestConvergedGossip
      latestConvergedGossip = newGossip
      bufferedEvents foreach publish
      bufferedEvents = Vector.empty
    }
    // publish internal SeenState for testing purposes
    diffSeen(oldGossip, newGossip) foreach publish
  }

  def publishInternalStats(currentStats: CurrentInternalStats): Unit = publish(currentStats)

  def publish(event: AnyRef): Unit = eventStream publish event

  def publishStart(): Unit =
    if ((latestGossip ne Gossip.empty) || (latestConvergedGossip ne Gossip.empty)) {
      clearState()
      publishCurrentClusterState(None)
    }

  def clearState(): Unit = {
    latestGossip = Gossip.empty
    latestConvergedGossip = Gossip.empty
  }
}

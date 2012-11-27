/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
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
    members: SortedSet[Member] = SortedSet.empty,
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
    def getUnreachable: java.util.Set[Member] = {
      import scala.collection.JavaConverters._
      unreachable.asJava
    }

    /**
     * Java API
     * Read only
     */
    def getSeenBy: java.util.Set[Address] = {
      import scala.collection.JavaConverters._
      seenBy.asJava
    }

    /**
     * Java API
     * @return address of current leader, or null if none
     */
    def getLeader: Address = leader orNull
  }

  /**
   * Marker interface for member related events.
   */
  sealed trait MemberEvent extends ClusterDomainEvent {
    def member: Member
  }

  /**
   * A new member joined the cluster. Only published after convergence.
   */
  case class MemberJoined(member: Member) extends MemberEvent {
    if (member.status != Joining) throw new IllegalArgumentException("Expected Joining status, got: " + member)
  }

  /**
   * Member status changed to Up. Only published after convergence.
   */
  case class MemberUp(member: Member) extends MemberEvent {
    if (member.status != Up) throw new IllegalArgumentException("Expected Up status, got: " + member)
  }

  /**
   * Member status changed to Leaving. Only published after convergence.
   */
  case class MemberLeft(member: Member) extends MemberEvent {
    if (member.status != Leaving) throw new IllegalArgumentException("Expected Leaving status, got: " + member)
  }

  /**
   * Member status changed to Exiting. Only published after convergence.
   */
  case class MemberExited(member: Member) extends MemberEvent {
    if (member.status != Exiting) throw new IllegalArgumentException("Expected Exiting status, got: " + member)
  }

  /**
   * Member status changed to Down. Only published after convergence.
   */
  case class MemberDowned(member: Member) extends MemberEvent {
    if (member.status != Down) throw new IllegalArgumentException("Expected Down status, got: " + member)
  }

  /**
   * Member completely removed from the cluster. Only published after convergence.
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
   * INTERNAL API
   * A member is considered as unreachable by the failure detector.
   */
  case class UnreachableMember(member: Member) extends ClusterDomainEvent

  /**
   * INTERNAL API
   *
   * Current snapshot of cluster member metrics. Published to subscribers.
   */
  case class ClusterMetricsChanged(nodes: Set[NodeMetrics]) extends ClusterDomainEvent

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
      val memberEvents = (newMembers ++ changedMembers) map { m ⇒
        m.status match {
          case Joining ⇒ MemberJoined(m)
          case Up      ⇒ MemberUp(m)
          case Leaving ⇒ MemberLeft(m)
          case Exiting ⇒ MemberExited(m)
          case _       ⇒ throw new IllegalStateException("Unexpected member status: " + m)
        }
      }

      val allNewUnreachable = newGossip.overview.unreachable -- oldGossip.overview.unreachable
      val newDowned = allNewUnreachable filter { _.status == Down }
      val downedEvents = newDowned map MemberDowned

      val unreachableGroupedByAddress =
        List(newGossip.overview.unreachable, oldGossip.overview.unreachable).flatten.groupBy(_.address)
      val unreachableDownMembers = unreachableGroupedByAddress collect {
        case (_, newMember :: oldMember :: Nil) if newMember.status == Down && newMember.status != oldMember.status ⇒
          newMember
      }
      val unreachableDownedEvents = unreachableDownMembers map MemberDowned

      val removedEvents = (oldGossip.members -- newGossip.members -- newGossip.overview.unreachable) map { m ⇒
        MemberRemoved(m.copy(status = Removed))
      }

      (new VectorBuilder[MemberEvent]() ++= memberEvents ++= downedEvents ++= unreachableDownedEvents
        ++= removedEvents).result()
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

  var latestGossip: Gossip = Gossip()
  var latestConvergedGossip: Gossip = Gossip()
  var memberEvents: immutable.Seq[MemberEvent] = immutable.Seq.empty

  def receive = {
    case PublishChanges(newGossip)            ⇒ publishChanges(newGossip)
    case currentStats: CurrentInternalStats   ⇒ publishInternalStats(currentStats)
    case PublishCurrentClusterState(receiver) ⇒ publishCurrentClusterState(receiver)
    case Subscribe(subscriber, to)            ⇒ subscribe(subscriber, to)
    case Unsubscribe(subscriber, to)          ⇒ unsubscribe(subscriber, to)
    case PublishEvent(event)                  ⇒ publish(event)
    case PublishStart                         ⇒ publishStart()
    case PublishDone                          ⇒ publishDone(sender)
  }

  def eventStream: EventStream = context.system.eventStream

  def publishCurrentClusterState(receiver: Option[ActorRef]): Unit = {
    // The state is a mix of converged and latest gossip to mimic what you
    // would have seen if you where listening to the events.
    val state = CurrentClusterState(
      members = latestConvergedGossip.members,
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
    diffUnreachable(oldGossip, newGossip) foreach { event ⇒
      publish(event)
      // notify DeathWatch about unreachable node
      publish(AddressTerminated(event.member.address))
    }
    // buffer up the MemberEvents waiting for convergence
    memberEvents ++= diffMemberEvents(oldGossip, newGossip)
    // if we have convergence then publish the MemberEvents and possibly a LeaderChanged
    if (newGossip.convergence) {
      val previousConvergedGossip = latestConvergedGossip
      latestConvergedGossip = newGossip
      memberEvents foreach publish
      memberEvents = immutable.Seq.empty
      diffLeader(previousConvergedGossip, latestConvergedGossip) foreach publish
    }
    // publish internal SeenState for testing purposes
    diffSeen(oldGossip, newGossip) foreach publish
  }

  def publishInternalStats(currentStats: CurrentInternalStats): Unit = publish(currentStats)

  def publish(event: AnyRef): Unit = eventStream publish event

  def publishStart(): Unit = clearState()

  def publishDone(receiver: ActorRef): Unit = {
    clearState()
    receiver ! PublishDoneFinished
  }

  def clearState(): Unit = {
    latestGossip = Gossip()
    latestConvergedGossip = Gossip()
  }
}

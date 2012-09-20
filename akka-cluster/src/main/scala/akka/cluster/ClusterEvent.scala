/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.collection.immutable.SortedSet
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
    convergence: Boolean = false,
    seenBy: Set[Address] = Set.empty,
    leader: Option[Address] = None) extends ClusterDomainEvent

  /**
   * Marker interface for member related events.
   */
  sealed trait MemberEvent extends ClusterDomainEvent {
    def member: Member
  }

  /**
   * A new member joined the cluster.
   */
  case class MemberJoined(member: Member) extends MemberEvent {
    if (member.status != Joining) throw new IllegalArgumentException("Expected Joining status, got: " + member)
  }

  /**
   * Member status changed to Up
   */
  case class MemberUp(member: Member) extends MemberEvent {
    if (member.status != Up) throw new IllegalArgumentException("Expected Up status, got: " + member)
  }

  /**
   * Member status changed to Leaving
   */
  case class MemberLeft(member: Member) extends MemberEvent {
    if (member.status != Leaving) throw new IllegalArgumentException("Expected Leaving status, got: " + member)
  }

  /**
   * Member status changed to Exiting
   */
  case class MemberExited(member: Member) extends MemberEvent {
    if (member.status != Exiting) throw new IllegalArgumentException("Expected Exiting status, got: " + member)
  }

  /**
   * A member is considered as unreachable by the failure detector.
   */
  case class MemberUnreachable(member: Member) extends MemberEvent

  /**
   * Member status changed to Down
   */
  case class MemberDowned(member: Member) extends MemberEvent {
    if (member.status != Down) throw new IllegalArgumentException("Expected Down status, got: " + member)
  }

  /**
   * Member completely removed from the cluster
   */
  case class MemberRemoved(member: Member) extends MemberEvent {
    if (member.status != Removed) throw new IllegalArgumentException("Expected Removed status, got: " + member)
  }

  /**
   * Cluster convergence state changed.
   */
  case class ConvergenceChanged(convergence: Boolean) extends ClusterDomainEvent

  /**
   * Leader of the cluster members changed. Only published after convergence.
   */
  case class LeaderChanged(leader: Option[Address]) extends ClusterDomainEvent

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
  private[cluster] def diff(oldGossip: Gossip, newGossip: Gossip): IndexedSeq[ClusterDomainEvent] = {
    val newMembers = newGossip.members -- oldGossip.members

    val membersGroupedByAddress = (newGossip.members.toList ++ oldGossip.members.toList).groupBy(_.address)
    val changedMembers = membersGroupedByAddress collect {
      case (_, newMember :: oldMember :: Nil) if newMember.status != oldMember.status ⇒ newMember
    }

    val memberEvents = (newMembers ++ changedMembers) map { m ⇒
      if (m.status == Joining) MemberJoined(m)
      else if (m.status == Up) MemberUp(m)
      else if (m.status == Leaving) MemberLeft(m)
      else if (m.status == Exiting) MemberExited(m)
      else throw new IllegalStateException("Unexpected member status: " + m)
    }

    val allNewUnreachable = newGossip.overview.unreachable -- oldGossip.overview.unreachable
    val (newDowned, newUnreachable) = allNewUnreachable partition { _.status == Down }
    val downedEvents = newDowned map MemberDowned
    val unreachableEvents = newUnreachable map MemberUnreachable

    val unreachableGroupedByAddress =
      (newGossip.overview.unreachable.toList ++ oldGossip.overview.unreachable.toList).groupBy(_.address)
    val unreachableDownMembers = unreachableGroupedByAddress collect {
      case (_, newMember :: oldMember :: Nil) if newMember.status == Down && newMember.status != oldMember.status ⇒
        newMember
    }
    val unreachableDownedEvents = unreachableDownMembers map MemberDowned

    val removedEvents = (oldGossip.members -- newGossip.members -- newGossip.overview.unreachable) map { m ⇒
      MemberRemoved(m.copy(status = Removed))
    }

    val newConvergence = newGossip.convergence
    val convergenceChanged = newConvergence != oldGossip.convergence
    val convergenceEvents = if (convergenceChanged) Seq(ConvergenceChanged(newConvergence)) else Seq.empty

    val leaderEvents =
      if (newGossip.leader != oldGossip.leader) Seq(LeaderChanged(newGossip.leader))
      else Seq.empty

    val newSeenBy = newGossip.seenBy
    val seenEvents =
      if (convergenceChanged || newSeenBy != oldGossip.seenBy) Seq(SeenChanged(newConvergence, newSeenBy))
      else Seq.empty

    memberEvents.toIndexedSeq ++ unreachableEvents ++ downedEvents ++ unreachableDownedEvents ++ removedEvents ++
      leaderEvents ++ convergenceEvents ++ seenEvents
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

  // Keep track of LeaderChanged event. Should not be published until
  // convergence, and it should only be published when leader actually
  // changed to another node. 3 states:
  // - None: No LeaderChanged detected yet, nothing published yet
  // - Some(Left): Stashed LeaderChanged to be published later, when convergence
  // - Some(Right): Latest published LeaderChanged
  var leaderChangedState: Option[Either[LeaderChanged, LeaderChanged]] = None

  def receive = {
    case PublishChanges(oldGossip, newGossip) ⇒ publishChanges(oldGossip, newGossip)
    case currentStats: CurrentInternalStats   ⇒ publishInternalStats(currentStats)
    case PublishCurrentClusterState(receiver) ⇒ publishCurrentClusterState(receiver)
    case Subscribe(subscriber, to)            ⇒ subscribe(subscriber, to)
    case Unsubscribe(subscriber)              ⇒ unsubscribe(subscriber)
    case PublishDone                          ⇒ sender ! PublishDone
  }

  def eventStream: EventStream = context.system.eventStream

  def publishCurrentClusterState(receiver: Option[ActorRef]): Unit = {
    val state = CurrentClusterState(
      members = latestGossip.members,
      unreachable = latestGossip.overview.unreachable,
      convergence = latestGossip.convergence,
      seenBy = latestGossip.seenBy,
      leader = latestGossip.leader)
    receiver match {
      case Some(ref) ⇒ ref ! state
      case None      ⇒ eventStream publish state
    }
  }

  def subscribe(subscriber: ActorRef, to: Class[_]): Unit = {
    publishCurrentClusterState(Some(subscriber))
    eventStream.subscribe(subscriber, to)
  }

  def unsubscribe(subscriber: ActorRef): Unit =
    eventStream.unsubscribe(subscriber)

  def publishChanges(oldGossip: Gossip, newGossip: Gossip): Unit = {
    // keep the latestGossip to be sent to new subscribers
    latestGossip = newGossip
    diff(oldGossip, newGossip) foreach { event ⇒
      event match {
        case x @ LeaderChanged(_) if leaderChangedState == Some(Right(x)) ⇒
        // skip, this leader has already been published

        case x @ LeaderChanged(_) if oldGossip.convergence && newGossip.convergence ⇒
          // leader changed and immediate convergence
          leaderChangedState = Some(Right(x))
          eventStream publish x

        case x: LeaderChanged ⇒
          // publish later, when convergence
          leaderChangedState = Some(Left(x))

        case ConvergenceChanged(true) ⇒
          // now it's convergence, publish eventual stashed LeaderChanged event
          leaderChangedState match {
            case Some(Left(x)) ⇒
              leaderChangedState = Some(Right(x))
              eventStream publish x

            case _ ⇒ // nothing stashed
          }
          eventStream publish event

        case MemberUnreachable(m) ⇒
          eventStream publish event
          // notify DeathWatch about unreachable node
          eventStream publish AddressTerminated(m.address)

        case _ ⇒
          // all other events
          eventStream publish event
      }
    }
  }

  def publishInternalStats(currentStats: CurrentInternalStats): Unit = {
    eventStream publish currentStats
  }
}

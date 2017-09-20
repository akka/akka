/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.{ Address, ExtendedActorSystem }
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.cluster.ClusterEvent.{ ClusterDomainEvent, CurrentClusterState, MemberEvent }
import akka.cluster._
import akka.japi.Util
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.{ ActorRef, ActorSystem, Extension, ExtensionId, Terminated }

import scala.collection.immutable

/**
 * Messages for subscribing to changes in the cluster state
 *
 * Not intended for user extension.
 */
@DoNotInherit
sealed trait ClusterStateSubscription

/**
 * Subscribe to cluster state changes. The initial state of the cluster will be sent as
 * a "replay" of the subscribed events.
 *
 * @param subscriber A subscriber that will receive events until it is unsubscribed or stops
 * @param eventClass The type of events to subscribe to, can be individual event types such as
 *                   `ReachabilityEvent` or one of the common supertypes, such as `MemberEvent` to get
 *                   all the subtypes of events.
 */
final case class Subscribe[A <: ClusterDomainEvent](
  subscriber: ActorRef[A],
  eventClass: Class[A]) extends ClusterStateSubscription

/**
 * Subscribe to this node being up, after sending this event the subscription is automatically
 * cancelled. If the node is already up the event is also sent to the subscriber. If the node was up
 * but is no more because it left or is leaving the cluster, no event is sent and the subscription
 * request is ignored.
 *
 * Note: Only emitted for the typed cluster.
 */
final case class SelfUp(currentClusterState: CurrentClusterState) extends ClusterDomainEvent

/**
 * Subscribe to this node being removed from the cluster. If the node was already removed from the cluster
 * when this subscription is created it will be responded to immediately from the subscriptions actor.
 *
 * Note: Only emitted for the typed cluster.
 */
final case class SelfRemoved(previousStatus: MemberStatus) extends ClusterDomainEvent

final case class Unsubscribe[T](subscriber: ActorRef[T]) extends ClusterStateSubscription
final case class GetCurrentState(recipient: ActorRef[CurrentClusterState]) extends ClusterStateSubscription

/**
 * Not intended for user extension.
 */
@DoNotInherit
sealed trait ClusterCommand

/**
 * Try to join this cluster node with the node specified by 'address'.
 *
 * An actor system can only join a cluster once. Additional attempts will be ignored.
 * When it has successfully joined it must be restarted to be able to join another
 * cluster or to join the same cluster again.
 *
 * The name of the [[akka.actor.ActorSystem]] must be the same for all members of a
 * cluster.
 */
final case class Join(address: Address) extends ClusterCommand

/**
 * Scala API: Join the specified seed nodes without defining them in config.
 * Especially useful from tests when Addresses are unknown before startup time.
 *
 * An actor system can only join a cluster once. Additional attempts will be ignored.
 * When it has successfully joined it must be restarted to be able to join another
 * cluster or to join the same cluster again.
 */
final case class JoinSeedNodes(seedNodes: immutable.Seq[Address]) extends ClusterCommand {

  /**
   * Java API: Join the specified seed nodes without defining them in config.
   * Especially useful from tests when Addresses are unknown before startup time.
   *
   * An actor system can only join a cluster once. Additional attempts will be ignored.
   * When it has successfully joined it must be restarted to be able to join another
   * cluster or to join the same cluster again.
   *
   * Creates a defensive copy of the list to ensure immutability.
   */
  def this(seedNodes: java.util.List[Address]) = this(Util.immutableSeq(seedNodes))

}

/**
 * Send command to issue state transition to LEAVING for the node specified by 'address'.
 * The member will go through the status changes [[MemberStatus]] `Leaving` (not published to
 * subscribers) followed by [[MemberStatus]] `Exiting` and finally [[MemberStatus]] `Removed`.
 *
 * Note that this command can be issued to any member in the cluster, not necessarily the
 * one that is leaving. The cluster extension, but not the actor system or JVM, of the
 * leaving member will be shutdown after the leader has changed status of the member to
 * Exiting. Thereafter the member will be removed from the cluster. Normally this is
 * handled automatically, but in case of network failures during this process it might
 * still be necessary to set the node’s status to Down in order to complete the removal.
 */
final case class Leave(address: Address) extends ClusterCommand

/**
 * Send command to DOWN the node specified by 'address'.
 *
 * When a member is considered by the failure detector to be unreachable the leader is not
 * allowed to perform its duties, such as changing status of new joining members to 'Up'.
 * The status of the unreachable member must be changed to 'Down', which can be done with
 * this method.
 */
final case class Down(address: Address) extends ClusterCommand

/**
 * Akka Typed Cluster API entry point
 */
object Cluster extends ExtensionId[Cluster] {

  def createExtension(system: ActorSystem[_]): Cluster = new AdapterClusterImpl(system)

  def get(system: ActorSystem[_]): Cluster = apply(system)
}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] object AdapterClusterImpl {

  private sealed trait SeenState
  private case object BeforeUp extends SeenState
  private case object Up extends SeenState
  private case class Removed(previousStatus: MemberStatus) extends SeenState

  private def subscriptionsBehavior(adaptedCluster: akka.cluster.Cluster) = Actor.deferred[ClusterStateSubscription] { ctx ⇒
    var seenState: SeenState = BeforeUp
    var upSubscribers: List[ActorRef[SelfUp]] = Nil
    var removedSubscribers: List[ActorRef[SelfRemoved]] = Nil

    adaptedCluster.subscribe(ctx.self.toUntyped, ClusterEvent.initialStateAsEvents, classOf[MemberEvent])

    // important to not eagerly refer to it or we get a cycle here
    lazy val cluster = Cluster(ctx.system)
    def onSelfMemberEvent(event: MemberEvent): Unit = {
      event match {
        case ClusterEvent.MemberUp(_) ⇒
          seenState = Up
          val upMessage = SelfUp(cluster.state)
          upSubscribers.foreach(_ ! upMessage)
          upSubscribers = Nil

        case ClusterEvent.MemberRemoved(_, previousStatus) ⇒
          seenState = Removed(previousStatus)
          val removedMessage = SelfRemoved(previousStatus)
          removedSubscribers.foreach(_ ! removedMessage)
          removedSubscribers = Nil

        case _ ⇒ // This is fine.
      }
    }

    Actor.immutable[AnyRef] { (ctx, msg) ⇒

      msg match {
        case Subscribe(subscriber: ActorRef[SelfUp] @unchecked, clazz) if clazz == classOf[SelfUp] ⇒
          seenState match {
            case Up ⇒ subscriber ! SelfUp(adaptedCluster.state)
            case BeforeUp ⇒
              ctx.watch(subscriber)
              upSubscribers = subscriber :: upSubscribers
            case _: Removed ⇒
            // self did join, but is now no longer up, we want to avoid subscribing
            // to not get a memory leak, but also not signal anything
          }
          Actor.same

        case Subscribe(subscriber: ActorRef[SelfRemoved] @unchecked, clazz) if clazz == classOf[SelfRemoved] ⇒
          seenState match {
            case BeforeUp | Up ⇒ removedSubscribers = subscriber :: removedSubscribers
            case Removed(s)    ⇒ subscriber ! SelfRemoved(s)
          }
          Actor.same

        case Subscribe(subscriber, eventClass) ⇒
          adaptedCluster.subscribe(subscriber.toUntyped, initialStateMode = ClusterEvent.initialStateAsEvents, eventClass)
          Actor.same

        case Unsubscribe(subscriber) ⇒
          adaptedCluster.unsubscribe(subscriber.toUntyped)
          Actor.same

        case GetCurrentState(sender) ⇒
          adaptedCluster.sendCurrentClusterState(sender.toUntyped)
          Actor.same

        case evt: MemberEvent if evt.member.uniqueAddress == cluster.selfMember.uniqueAddress ⇒
          onSelfMemberEvent(evt)
          Actor.same

        case _: MemberEvent ⇒
          Actor.same

      }
    }.onSignal {

      case (_, Terminated(ref)) ⇒
        upSubscribers = upSubscribers.filterNot(_ == ref)
        removedSubscribers = removedSubscribers.filterNot(_ == ref)
        Actor.same

    }.narrow[ClusterStateSubscription]
  }

  private def managerBehavior(adaptedCluster: akka.cluster.Cluster) = Actor.immutable[ClusterCommand]((ctx, msg) ⇒
    msg match {
      case Join(address) ⇒
        adaptedCluster.join(address)
        Actor.same

      case Leave(address) ⇒
        adaptedCluster.leave(address)
        Actor.same

      case Down(address) ⇒
        adaptedCluster.down(address)
        Actor.same

      case JoinSeedNodes(addresses) ⇒
        adaptedCluster.joinSeedNodes(addresses)
        Actor.same

    }

  )

}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] final class AdapterClusterImpl(system: ActorSystem[_]) extends Cluster {
  import AdapterClusterImpl._

  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted actor systems can be used for cluster features")
  private val untypedSystem = ActorSystemAdapter.toUntyped(system)
  private def extendedUntyped = untypedSystem.asInstanceOf[ExtendedActorSystem]
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

  override def selfMember = untypedCluster.selfMember
  override def isTerminated = untypedCluster.isTerminated
  override def state = untypedCluster.state

  // must not be lazy as it also updates the cached selfMember
  override val subscriptions: ActorRef[ClusterStateSubscription] = extendedUntyped.systemActorOf(
    PropsAdapter(subscriptionsBehavior(untypedCluster)), "clusterStateSubscriptions")

  override lazy val manager: ActorRef[ClusterCommand] = extendedUntyped.systemActorOf(
    PropsAdapter(managerBehavior(untypedCluster)), "clusterCommandManager")

}

/**
 * Not intended for user extension.
 */
@DoNotInherit
sealed trait Cluster extends Extension {

  /** Details about this cluster node itself */
  def selfMember: Member

  /** Returns true if this cluster instance has be shutdown. */
  def isTerminated: Boolean

  /** Current snapshot state of the cluster. */
  def state: CurrentClusterState

  /**
   * @return an actor that allows for subscribing to messages when the cluster state changes
   */
  def subscriptions: ActorRef[ClusterStateSubscription]

  /**
   * @return an actor that accepts commands to join, leave and down nodes in a cluster
   */
  def manager: ActorRef[ClusterCommand]

}

/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.{ Address, ExtendedActorSystem }
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.cluster.ClusterEvent.{ ClusterDomainEvent, CurrentClusterState, MemberUp }
import akka.cluster._
import akka.pattern.FutureRef
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.{ ActorRef, ActorSystem, Extension, ExtensionId, Terminated }
import akka.typed.scaladsl.adapter._
import akka.util.Timeout

import scala.concurrent.duration._
import scala.collection.immutable
import scala.concurrent.Await
import scala.reflect.ClassTag

/**
 * Messages for subscribing to changes in the cluster state
 *
 * Not intended for user extension.
 */
@DoNotInherit
sealed trait ClusterStateSubscription

object Subscribe {
  def apply[A <: ClusterDomainEvent: ClassTag](subscriber: ActorRef[A]) =
    new Subscribe[A, A](subscriber, implicitly[ClassTag[A]].runtimeClass.asInstanceOf[Class[A]])
}

/**
 * Subscribe to cluster state changes. The initial state of the cluster will be sent as
 * a "replay" of the subscribed events.
 *
 * If all you are interested in is to react on this node becoming a part of the cluster,
 * see [[OnSelfUp]]
 *
 * @param subscriber A subscriber that will receive events until it is unsubscribed or stops
 * @param eventClass The type of events to subscribe to, can be individual event types such as
 *                   `ReachabilityEvent` or one of the common supertypes, such as `MemberEvent` to get
 *                   all the subtypes of events.
 */
final case class Subscribe[A <: ClusterDomainEvent, S <: A](
  subscriber: ActorRef[A],
  eventClass: Class[S]) extends ClusterStateSubscription

/**
 * Subscribe to this node being up, after sending this event the subscription is automatically
 * cancelled. If the node is already up the event is also sent to the subscriber.
 */
final case class OnSelfUp(subscriber: ActorRef[SelfUp]) extends ClusterStateSubscription

/**
 * @param currentClusterState The cluster state snapshot from when the node became Up.
 */
final case class SelfUp(currentClusterState: CurrentClusterState)

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
 * Join the specified seed nodes without defining them in config.
 * Especially useful from tests when Addresses are unknown before startup time.
 *
 * An actor system can only join a cluster once. Additional attempts will be ignored.
 * When it has successfully joined it must be restarted to be able to join another
 * cluster or to join the same cluster again.
 */
final case class JoinSeedNodes(seedNodes: immutable.Seq[Address]) extends ClusterCommand

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

  def createExtension(system: ActorSystem[_]): Cluster =
    new AdapterClusterImpl(system)

}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] object AdapterClusterImpl {

  private def subscriptionsBehavior(adaptedCluster: akka.cluster.Cluster) = Actor.deferred[ClusterStateSubscription] { ctx ⇒
    val cluster = Cluster(ctx.system)
    var upSubscribers: List[ActorRef[SelfUp]] = Nil
    if (cluster.selfMember.status != MemberStatus.Up)
      adaptedCluster.subscribe(ctx.self.toUntyped, ClusterEvent.initialStateAsEvents, classOf[MemberUp])

    Actor.immutable[AnyRef] { (ctx, msg) ⇒
      msg match {

        case Subscribe(subscriber, eventClass) ⇒
          adaptedCluster.subscribe(subscriber.toUntyped, initialStateMode = ClusterEvent.initialStateAsEvents, eventClass)
          Actor.same

        case Unsubscribe(subscriber) ⇒
          adaptedCluster.unsubscribe(subscriber.toUntyped)
          Actor.same

        case OnSelfUp(subscriber) ⇒
          if (cluster.selfMember.status == MemberStatus.Up) subscriber ! SelfUp(adaptedCluster.state)
          else {
            ctx.watch(subscriber)
            upSubscribers = subscriber :: upSubscribers
          }
          Actor.same

        case GetCurrentState(sender) ⇒
          sender ! adaptedCluster.state
          Actor.same

        case ClusterEvent.MemberUp(member) if member.uniqueAddress == cluster.selfMember.uniqueAddress ⇒
          upSubscribers.foreach(_ ! SelfUp(adaptedCluster.state))
          upSubscribers = Nil
          Actor.same

        case _: ClusterEvent.MemberUp ⇒
          Actor.same

      }
    }.onSignal {

      case (_, Terminated(ref)) ⇒
        upSubscribers = upSubscribers.filterNot(_ == ref)
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
  private val adaptedCluster = akka.cluster.Cluster(untypedSystem)

  def selfMember =
    adaptedCluster.state.members.find(_.uniqueAddress == adaptedCluster.selfUniqueAddress).getOrElse(
      // Not sure if this fake-it-until-you-make it strategy makes sense, but wanted to avoid using an option here
      new Member(adaptedCluster.selfUniqueAddress, 0, MemberStatus.Joining, adaptedCluster.selfRoles)
    )
  def isTerminated = adaptedCluster.isTerminated
  def state = adaptedCluster.state

  override lazy val subscriptions: ActorRef[ClusterStateSubscription] = extendedUntyped.systemActorOf(
    PropsAdapter(subscriptionsBehavior(adaptedCluster)), "cluster-state-subscriptions")
  override lazy val manager: ActorRef[ClusterCommand] = extendedUntyped.systemActorOf(
    PropsAdapter(managerBehavior(adaptedCluster)), "cluster-command-manager")

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

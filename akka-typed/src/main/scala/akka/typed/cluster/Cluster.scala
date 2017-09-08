/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import akka.actor.Address
import akka.annotation.DoNotInherit
import akka.cluster.ClusterEvent.{ ClusterDomainEvent, CurrentClusterState, InitialStateAsSnapshot, SubscriptionInitialStateMode }
import akka.cluster._
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.{ ActorRef, ActorSystem, Extension, ExtensionId }

import scala.collection.immutable
import scala.reflect.ClassTag

object Cluster extends ExtensionId[Cluster] {

  sealed trait ClusterStateSubscription
  // we can't have initial cluster as snapshot as that wouldn't be a ClusterDomainEvent?
  object Subscribe {
    def apply[T <: ClusterDomainEvent: ClassTag](
      subscriber: ActorRef[T]) =
      new Subscribe[T](subscriber, Set(implicitly[ClassTag[T]].runtimeClass))
  }
  final case class Subscribe[T <: ClusterDomainEvent](
    subscriber: ActorRef[T],
    // Not sure that/how we can enforce the clases, if we want multiple messages, but would be nice
    // maybe just enforce using multiple messages in that case? or provide a small dsl "andAlso[OtherT]"
    classes: Set[Class[_]])
    extends ClusterStateSubscription
  final case class Unsubscribe[T](subscriber: ActorRef[T]) extends ClusterStateSubscription
  final case class GetCurrentState(recipient: ActorRef[CurrentClusterState]) extends ClusterStateSubscription

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

  def createExtension(system: ActorSystem[_]): Cluster = {
    require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted actor systems can be used for cluster features")
    ???
  }
}

@DoNotInherit
sealed trait Cluster extends Extension {

  import Cluster._

  // Note: not sure about this, but when five accessors are prefixed with `self` that kind of points towards the need
  // for a scope/namespace for them

  trait ClusterSelf {
    /** The address of this cluster member. */
    def address: Address

    /** Data center to which this node belongs to (defaults to "default" if not configured explicitly) */
    def dataCenter: akka.cluster.ClusterSettings.DataCenter

    /** Scala API: roles that this member has */
    def roles: Set[String]

    // TODO javadsl/scaladsl rather than a coveritall API?
    /** Java API: roles that this member has */
    def getRoles: java.util.Set[String]

    // and maybe, just maybe these two belongs here as well, as they are about the cluster node itself -
    // the self-view of the state and whether the self-has been shutdown?
    /** Returns true if this cluster instance has be shutdown. */
    def isTerminated: Boolean

    // also, the mutable snapshot feels icky but I guess it simplifies things enough to keep?
    /** Current snapshot state of the cluster. */
    def state: CurrentClusterState
  }

  /** Details about this cluster node itself */
  def self: ClusterSelf

  // I'm thinking settings and failure detectors are really implementation details that has leaked
  // and should not be accessible through the public api?

  /**
   * @return an actor that allows for subscribing to messages when the cluster state changes
   */
  def subscriptions: ActorRef[ClusterStateSubscription]

  /**
   * @return an actor that accepts commands to join, leave and down nodes in a cluster
   */
  def manager: ActorRef[ClusterCommand]

  // These ones I think should be removed in favour of subscribing to events because that makes how it is executed more clear
  // and also gives less API surface
  // problem is then that actor may be dead on remove, but that is a general problem for all actors so shouldn't be suprising?
  // Or we could keep a subscription-actor alive at all times and handle that there?
  /*
  def registerOnMemberUp[T](code: ⇒ T): Unit =
    registerOnMemberUp(new Runnable { def run() = code })

  def registerOnMemberUp(callback: Runnable): Unit =
    clusterDaemons ! InternalClusterAction.AddOnMemberUpListener(callback)

  def registerOnMemberRemoved[T](code: ⇒ T): Unit =
    registerOnMemberRemoved(new Runnable { override def run(): Unit = code })

  // also: it's racy?
  def registerOnMemberRemoved(callback: Runnable): Unit = {
    if (_isTerminated.get())
      callback.run()
    else
      clusterDaemons ! InternalClusterAction.AddOnMemberRemovedListener(callback)
  }
  */

}

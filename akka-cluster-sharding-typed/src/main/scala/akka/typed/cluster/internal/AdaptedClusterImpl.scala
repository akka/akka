/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster.internal

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.{ ClusterEvent, MemberStatus }
import akka.typed.{ ActorRef, ActorSystem, Terminated }
import akka.typed.cluster._
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._

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
  private val untypedSystem = system.toUntyped
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

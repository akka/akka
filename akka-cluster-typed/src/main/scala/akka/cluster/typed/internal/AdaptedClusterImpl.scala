/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed.internal

import akka.actor.typed.Props
import akka.annotation.InternalApi
import akka.cluster.ClusterEvent.MemberEvent
import akka.cluster.{ ClusterEvent, Member, MemberStatus }
import akka.actor.typed.{ ActorRef, ActorSystem, Terminated }
import akka.cluster.typed._
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] object AdapterClusterImpl {

  private sealed trait SeenState
  private case object BeforeUp extends SeenState
  private case object Up extends SeenState
  private case class Removed(previousStatus: MemberStatus) extends SeenState

  private def subscriptionsBehavior(adaptedCluster: akka.cluster.Cluster) = Behaviors.setup[ClusterStateSubscription] {
    ctx =>
      var seenState: SeenState = BeforeUp
      var upSubscribers: List[ActorRef[SelfUp]] = Nil
      var removedSubscribers: List[ActorRef[SelfRemoved]] = Nil

      adaptedCluster.subscribe(ctx.self.toUntyped, ClusterEvent.initialStateAsEvents, classOf[MemberEvent])

      // important to not eagerly refer to it or we get a cycle here
      lazy val cluster = Cluster(ctx.system)
      def onSelfMemberEvent(event: MemberEvent): Unit = {
        event match {
          case ClusterEvent.MemberUp(_) =>
            seenState = Up
            val upMessage = SelfUp(cluster.state)
            upSubscribers.foreach(_ ! upMessage)
            upSubscribers = Nil

          case ClusterEvent.MemberRemoved(_, previousStatus) =>
            seenState = Removed(previousStatus)
            val removedMessage = SelfRemoved(previousStatus)
            removedSubscribers.foreach(_ ! removedMessage)
            removedSubscribers = Nil

          case _ => // This is fine.
        }
      }

      Behaviors
        .receive[AnyRef] { (ctx, msg) =>
          msg match {
            case Subscribe(subscriber: ActorRef[SelfUp] @unchecked, clazz) if clazz == classOf[SelfUp] =>
              seenState match {
                case Up => subscriber ! SelfUp(adaptedCluster.state)
                case BeforeUp =>
                  ctx.watch(subscriber)
                  upSubscribers = subscriber :: upSubscribers
                case _: Removed =>
                // self did join, but is now no longer up, we want to avoid subscribing
                // to not get a memory leak, but also not signal anything
              }
              Behaviors.same

            case Subscribe(subscriber: ActorRef[SelfRemoved] @unchecked, clazz) if clazz == classOf[SelfRemoved] =>
              seenState match {
                case BeforeUp | Up => removedSubscribers = subscriber :: removedSubscribers
                case Removed(s)    => subscriber ! SelfRemoved(s)
              }
              Behaviors.same

            case Subscribe(subscriber, eventClass) =>
              adaptedCluster
                .subscribe(subscriber.toUntyped, initialStateMode = ClusterEvent.initialStateAsEvents, eventClass)
              Behaviors.same

            case Unsubscribe(subscriber) =>
              adaptedCluster.unsubscribe(subscriber.toUntyped)
              Behaviors.same

            case GetCurrentState(sender) =>
              adaptedCluster.sendCurrentClusterState(sender.toUntyped)
              Behaviors.same

            case evt: MemberEvent if evt.member.uniqueAddress == cluster.selfMember.uniqueAddress =>
              onSelfMemberEvent(evt)
              Behaviors.same

            case _: MemberEvent =>
              Behaviors.same

          }
        }
        .receiveSignal {

          case (_, Terminated(ref)) =>
            upSubscribers = upSubscribers.filterNot(_ == ref)
            removedSubscribers = removedSubscribers.filterNot(_ == ref)
            Behaviors.same

        }
        .narrow[ClusterStateSubscription]
  }

  private def managerBehavior(adaptedCluster: akka.cluster.Cluster) =
    Behaviors.receive[ClusterCommand]((_, msg) =>
      msg match {
        case Join(address) =>
          adaptedCluster.join(address)
          Behaviors.same

        case Leave(address) =>
          adaptedCluster.leave(address)
          Behaviors.same

        case Down(address) =>
          adaptedCluster.down(address)
          Behaviors.same

        case JoinSeedNodes(addresses) =>
          adaptedCluster.joinSeedNodes(addresses)
          Behaviors.same

      })

}

/**
 * INTERNAL API:
 */
@InternalApi
private[akka] final class AdapterClusterImpl(system: ActorSystem[_]) extends Cluster {
  import AdapterClusterImpl._

  require(system.isInstanceOf[ActorSystemAdapter[_]], "only adapted actor systems can be used for cluster features")
  private val untypedCluster = akka.cluster.Cluster(system.toUntyped)

  override def selfMember: Member = untypedCluster.selfMember
  override def isTerminated: Boolean = untypedCluster.isTerminated
  override def state: ClusterEvent.CurrentClusterState = untypedCluster.state

  // must not be lazy as it also updates the cached selfMember
  override val subscriptions: ActorRef[ClusterStateSubscription] =
    system.internalSystemActorOf(subscriptionsBehavior(untypedCluster), "clusterStateSubscriptions", Props.empty)

  override lazy val manager: ActorRef[ClusterCommand] =
    system.internalSystemActorOf(managerBehavior(untypedCluster), "clusterCommandManager", Props.empty)

}

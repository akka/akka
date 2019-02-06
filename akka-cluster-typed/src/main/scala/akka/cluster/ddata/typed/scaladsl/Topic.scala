/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata.typed.scaladsl
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.DoNotInherit

object Topic {

  /**
   * NOT FOR USER EXTENSION
   */
  @DoNotInherit
  sealed trait TopicCommand

}

class Topic[T](serviceKey: ServiceKey[T]) {
  import Topic._

  final case class Subscribe(subscriber: ActorRef[T]) extends TopicCommand
  final case class Unsubscribe(subscriber: ActorRef[T]) extends TopicCommand
  final case class Publish(msg: T) extends TopicCommand

  private case class RemoteSubscribersChanged(subscriber: Set[ActorRef[T]]) extends TopicCommand
  private case class WasPublished(t: T) extends TopicCommand

  def behavior[T]: Behavior[TopicCommand] = noSubscribersBehavior


  private def noSubscribersBehavior: Behavior[TopicCommand] = Behaviors.receiveMessage {
    case Subscribe(subscriber) =>
      Behaviors.setup { ctx =>
        val remoteMessageAdapter = ctx.messageAdapter {
          case t: T @unchecked => WasPublished(t)
        }
        ctx.system.receptionist ! Receptionist.register(serviceKey, remoteMessageAdapter)

        val remoteSubscriptionAdapter = ctx.messageAdapter {
          case serviceKey.Listing(refs) =>
            RemoteSubscribersChanged(refs)
        }
        ctx.system.receptionist ! Receptionist.subscribe(serviceKey, remoteSubscriptionAdapter)
        subscribedBehavior(subscriber :: Nil, Set.empty)
      }
    case _ => // unsubscribe, publish or wrapped message
      Behaviors.same
  }

  private def subscribedBehavior(localSubscribers: List[ActorRef[T]], remoteSubscribers: Set[ActorRef[T]]): Behavior[TopicCommand] =
    if (localSubscribers.isEmpty) {
      // FIXME no way to deregister from receptionist without stopping ourselves
      noSubscribersBehavior
    } else {
      Behaviors.receive { (ctx, msg) =>
        msg match {
          case Subscribe(subscriber) =>
            subscribedBehavior(subscriber :: localSubscribers, remoteSubscribers)
          case Unsubscribe(subscriber) =>
            subscribedBehavior(localSubscribers.filterNot(_ == subscriber), remoteSubscribers)
          case RemoteSubscribersChanged(newRemoteSubscribers) =>
            subscribedBehavior(localSubscribers, newRemoteSubscribers)
          case Publish(msg) =>
            localSubscribers.foreach(_ ! msg)
            remoteSubscribers.foreach(_ ! msg)
            Behaviors.same
        }
      }
    }




}

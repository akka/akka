/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.pubsub

import akka.actor.Dropped
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.pubsub.Topic
import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.InternalApi

import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object TopicImpl {

  trait Command[T]

  final case class TopicInstancesUpdated[T](topics: Set[ActorRef[TopicImpl.Command[T]]]) extends Command[T]
  final case class MessagePublished[T](message: T) extends Command[T]
  final case class SubscriberDied[T](subscriber: ActorRef[T]) extends Command[T]
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class TopicImpl[T](topicName: String, context: ActorContext[TopicImpl.Command[T]])(
    implicit classTag: ClassTag[T])
    extends AbstractBehavior[TopicImpl.Command[T]](context) {

  /*
   * The topic actor keeps a local set of subscribers, whenever that is non-empty it registers itself for
   * a topic service key, and when it becomes empty again it deregisters from the service key. Published
   * messages go to all currently known topics registered for the topic service key and the individual topic
   * instances then broadcast the message to all local subscribers. This achieves deduplication for nodes
   * with multiple subscribers and avoids sending to nodes without any subscribers.
   */

  import TopicImpl._

  private val topicServiceKey = ServiceKey[TopicImpl.Command[T]](topicName)
  context.log.debugN("Starting up pub-sub topic [{}] for messages of type [{}]", topicName, classTag.runtimeClass)

  private var topics = Set.empty[ActorRef[TopicImpl.Command[T]]]
  private var localSubscribers = Set.empty[ActorRef[T]]

  private def receptionist = context.system.receptionist
  private val receptionistAdapter = context.messageAdapter[Receptionist.Listing] {
    case topicServiceKey.Listing(topics) => TopicInstancesUpdated(topics)
  }
  receptionist ! Receptionist.Subscribe(topicServiceKey, receptionistAdapter)

  override def onMessage(msg: TopicImpl.Command[T]): Behavior[TopicImpl.Command[T]] = msg match {

    case Topic.Publish(message) =>
      if (topics.isEmpty) {
        context.log.trace("Publishing message of type [{}] but no subscribers, dropping", msg.getClass)
        context.system.deadLetters ! Dropped(message, "No topic subscribers known", context.self.toClassic)
      } else {
        context.log.trace("Publishing message of type [{}]", msg.getClass)
        val pub = MessagePublished(message)
        topics.foreach(_ ! pub)
      }
      this

    case MessagePublished(msg) =>
      context.log.trace("Message of type [{}] published", msg.getClass)
      localSubscribers.foreach(_ ! msg)
      this

    case Topic.Subscribe(subscriber) =>
      context.watchWith(subscriber, SubscriberDied(subscriber))
      localSubscribers = localSubscribers + subscriber
      if (localSubscribers.size == 1) {
        context.log.debug(
          "Local subscriber [{}] added, went from no subscribers to one, subscribing to receptionist",
          subscriber)
        // we went from no subscribers to one, register to the receptionist
        receptionist ! Receptionist.Register(topicServiceKey, context.self)
      } else {
        context.log.debug("Local subscriber [{}] added", subscriber)
      }
      this

    case Topic.Unsubscribe(subscriber) =>
      context.unwatch(subscriber)
      localSubscribers = localSubscribers.filterNot(_ == subscriber)
      if (localSubscribers.isEmpty) {
        context.log.debug("Last local subscriber [{}] unsubscribed, deregistering from receptionist", subscriber)
        // that was the lost subscriber, deregister from the receptionist
        receptionist ! Receptionist.Deregister(topicServiceKey, context.self)
      } else {
        context.log.debug("Local subscriber [{}] unsubscribed", subscriber)
      }
      this

    case SubscriberDied(subscriber) =>
      localSubscribers = localSubscribers.filterNot(_ == subscriber)
      if (localSubscribers.isEmpty) {
        context.log.debug("Last local subscriber [{}] terminated, deregistering from receptionist", subscriber)
        // that was the lost subscriber, deregister from the receptionist
        receptionist ! Receptionist.Deregister(topicServiceKey, context.self)
      } else {
        context.log.debug("Local subscriber [{}] terminated, removing from subscriber list", subscriber)
      }
      this

    case TopicInstancesUpdated(newTopics) =>
      context.log.debug("Topic list updated [{}]", newTopics)
      topics = newTopics
      this
  }
}

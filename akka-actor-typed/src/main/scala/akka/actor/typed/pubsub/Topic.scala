/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.pubsub

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.internal.pubsub.TopicImpl
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.DoNotInherit

import scala.reflect.ClassTag

/**
 * A pub sub topic is an actor that handles subscribing to a topic and publishing messages to all subscribed actors.
 *
 * It is mostly useful in a clustered setting, where it is intended to be started once on every node that want to
 * house subscribers or publish messages to the topic, but it also works in a local setting without cluster.
 *
 * In a clustered context messages are deduplicated so that there is at most one message sent to each node for
 * each publish and if there are no subscribers on a node, no message is sent to it. Note that the list of subscribers
 * is eventually consistent and there are no delivery guarantees built in.
 *
 * Each topic results in a [[akka.actor.typed.receptionist.ServiceKey]] in the [[akka.actor.typed.receptionist.Receptionist]]
 * so the same scaling recommendation holds for topics, see docs:
 * https://doc.akka.io/docs/akka/current/typed/actor-discovery.html#receptionist-scalability
 */
object Topic {

  /**
   * Not for user extension
   */
  @DoNotInherit
  sealed trait Command[T] extends TopicImpl.Command[T]

  /**
   * Publish the message to all currently known subscribers.
   */
  final case class Publish[T](message: T) extends Command[T]

  /**
   * Send the message to one randomly choosen subscriber.
   */
  final case class Send[T](message: T, preferLocal: Boolean) extends Command[T]

  /**
   * Subscribe to this topic. Should only be used for local subscribers.
   */
  final case class Subscribe[T](subscriber: ActorRef[T]) extends Command[T]

  /**
   * Unsubscribe a previously subscribed actor from this topic.
   */
  final case class Unsubscribe[T](subscriber: ActorRef[T]) extends Command[T]

  /**
   * Scala API: Create a topic actor behavior for the given topic name and message type.
   */
  def apply[T <: AnyRef](topicName: String)(implicit classTag: ClassTag[T]): Behavior[Command[T]] =
    Behaviors.setup[TopicImpl.Command[T]](context => new TopicImpl[T](topicName, context)).narrow

  /**
   * Java API: Create a topic actor behavior for the given topic name and message class
   */
  def create[T <: AnyRef](messageClass: Class[T], topicName: String): Behavior[Command[T]] = {
    implicit val classTag: ClassTag[T] = ClassTag(messageClass)
    apply[T](topicName)
  }

}

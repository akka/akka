/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.pubsub

import java.util.concurrent.ConcurrentHashMap

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.actor.typed.pubsub.Topic

import scala.reflect.ClassTag

/**
 * Registry keeping a single instance of a topic per name+type and actor system
 *
 * INTERNAL API
 */
// FIXME: could potentially be made public, not entirely sure if useful enough, normal use would be injection of topic ref
private[akka] object TopicRegistry extends ExtensionId[TopicRegistry] {
  override def createExtension(system: ActorSystem[_]): TopicRegistry =
    new TopicRegistry(system)
}

/**
 * INTERNAL API
 */
private[akka] final class TopicRegistry(system: ActorSystem[_]) extends Extension {
  private val registry = new ConcurrentHashMap[(String, Class[_]), ActorRef[Topic.Command[Any]]]()

  def topicFor[T](topicName: String)(implicit tag: ClassTag[T]): ActorRef[Topic.Command[T]] =
    registry
      .computeIfAbsent(
        (topicName, tag.runtimeClass),
        (_) => system.systemActorOf(Topic[T](topicName), topicName).asInstanceOf[ActorRef[Topic.Command[Any]]])
      .asInstanceOf[ActorRef[Topic.Command[T]]]
}

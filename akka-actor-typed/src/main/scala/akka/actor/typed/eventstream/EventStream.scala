/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import akka.actor.typed.internal.eventstream.SystemEventStream
import akka.actor.typed._
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.InternalApi

import scala.reflect.ClassTag

/**
 * INTERNAL API
 *
 * Exposes a typed actor that interacts with the [[akka.actor.ActorSystem.eventStream]].
 *
 * It is used as an extension to ensure a single instance per actor system.
 */
@InternalApi private[akka] class EventStream(actorSystem: ActorSystem[_]) extends Extension {
  val ref: ActorRef[EventStream.Command] =
    actorSystem.internalSystemActorOf(SystemEventStream.behavior, "eventstream", Props.empty)
}

object EventStream extends ExtensionId[EventStream] {

  sealed trait Command
  case class Publish[E](event: E) extends Command
  case class Subscribe[E](subscriber: ActorRef[E])(implicit classTag: ClassTag[E]) extends Command {
    private[akka] def topic: Class[_] = classTag.runtimeClass
  }

  case class Unsubscribe[E](subscriber: ActorRef[E]) extends Command

  override def createExtension(system: ActorSystem[_]): EventStream = new EventStream(system)
}

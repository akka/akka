/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import akka.actor.typed.internal.eventstream.SystemEventStream
import akka.actor.typed._
import akka.actor.typed.scaladsl.adapter._
import akka.annotation.{ DoNotInherit, InternalApi }

import scala.reflect.ClassTag

/**
 * INTERNAL API
 *
 * Exposes a typed actor that interacts with the [[akka.actor.ActorSystem.eventStream]].
 *
 * It is used as an extension to ensure a single instance per actor system.
 */
@InternalApi private[akka] final class EventStream(actorSystem: ActorSystem[_]) extends Extension {
  val ref: ActorRef[EventStream.Command] =
    actorSystem.internalSystemActorOf(SystemEventStream.behavior, "eventstream", Props.empty)
}

object EventStream extends ExtensionId[EventStream] {

  /**
   * Not for user Extension
   */
  @DoNotInherit sealed trait Command

  /**
   * Publish an event of type E
   * @param event
   * @tparam E
   */
  final case class Publish[E](event: E) extends Command

  /**
   * Subscribe a typed actor to listen for types or subtypes of E.
   * ==Simple example==
   * {{{
   *   sealed trait A
   *   case object A1 extends A
   *   //listen for all As
   *   def subscribe(actorSystem: ActorSystem[_], actorRef: ActorRef[A]) =
   *     actorSystem.eventStream ! Subscribe(actorRef)
   *   //listen for A1s only
   *   def subscribe(actorSystem: ActorSystem[_], actorRef: ActorRef[A]) =
   *     actorSystem.eventStream ! Subscribe[A1](actorRef)
   * }}}
   *
   * @param subscriber
   * @param classTag
   * @tparam E
   */
  final case class Subscribe[E](subscriber: ActorRef[E])(implicit classTag: ClassTag[E]) extends Command {
    private[akka] def topic: Class[_] = classTag.runtimeClass
  }

  /**
    * Unsubscribe an actor ref from the event stream
    * @param subscriber
    * @tparam E
    */
  final case class Unsubscribe[E](subscriber: ActorRef[E]) extends Command

  override def createExtension(system: ActorSystem[_]): EventStream = new EventStream(system)

  /**
   * Java API.
   */
  def subscribe[E](subscriber: ActorRef[E], clazz: java.lang.Class[E]): Subscribe[E] =
    Subscribe(subscriber)(ClassTag(clazz))

}

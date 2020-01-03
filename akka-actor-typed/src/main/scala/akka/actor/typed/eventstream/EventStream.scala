/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import akka.actor.typed.ActorRef
import akka.annotation.{ DoNotInherit, InternalApi }
import scala.reflect.ClassTag

object EventStream {

  /**
   * The set of commands accepted by the [[akka.actor.typed.ActorSystem.eventStream]].
   *
   * Not for user Extension
   */
  @DoNotInherit sealed trait Command

  /**
   * Publish an event of type E by sending this command to
   * the [[akka.actor.typed.ActorSystem.eventStream]].
   */
  final case class Publish[E](event: E) extends Command

  /**
   * Subscribe a typed actor to listen for types or subtypes of E
   * by sending this command to the [[akka.actor.typed.ActorSystem.eventStream]].
   *
   * ==Simple example==
   * {{{
   *   sealed trait A
   *   case object A1 extends A
   *   //listen for all As
   *   def subscribe(actorSystem: ActorSystem[_], actorRef: ActorRef[A]) =
   *     actorSystem.eventStream ! EventStream.Subscribe(actorRef)
   *   //listen for A1s only
   *   def subscribe(actorSystem: ActorSystem[_], actorRef: ActorRef[A]) =
   *     actorSystem.eventStream ! EventStream.Subscribe[A1](actorRef)
   * }}}
   *
   */
  final case class Subscribe[E](subscriber: ActorRef[E])(implicit classTag: ClassTag[E]) extends Command {

    /**
     * Java API.
     */
    def this(clazz: Class[E], subscriber: ActorRef[E]) = this(subscriber)(ClassTag(clazz))

    /**
     * INTERNAL API
     */
    @InternalApi private[akka] def topic: Class[_] = classTag.runtimeClass
  }

  /**
   * Unsubscribe an actor ref from the event stream
   * by sending this command to the [[akka.actor.typed.ActorSystem.eventStream]].
   */
  final case class Unsubscribe[E](subscriber: ActorRef[E]) extends Command

}

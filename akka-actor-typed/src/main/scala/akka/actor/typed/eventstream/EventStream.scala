/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import akka.actor.typed.ActorRef
import akka.annotation.{ DoNotInherit, InternalApi }

import scala.reflect.ClassTag

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

object Publish {

  /**
   * Java API.
   */
  def of[E](event: E): Publish[E] = apply(event)
}

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

  /**
   * Java API.
   */
  def this(clazz: Class[E], subscriber: ActorRef[E]) = this(subscriber)(ClassTag(clazz))

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def topic: Class[_] = classTag.runtimeClass
}

object Subscribe {

  /**
   * Java API.
   */
  def of[E](clazz: java.lang.Class[E], subscriber: ActorRef[E]): Subscribe[E] =
    Subscribe(subscriber)(ClassTag(clazz))
}

/**
 * Unsubscribe an actor ref from the event stream
 * @param subscriber
 * @tparam E
 */
final case class Unsubscribe[E](subscriber: ActorRef[E]) extends Command

object Unsubscribe {

  /**
   * Java API.
   */
  def of[E](subscriber: ActorRef[E]): Unsubscribe[E] = apply(subscriber)
}

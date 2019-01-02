/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import scala.collection.immutable

import akka.persistence.typed.{ SideEffect, javadsl, scaladsl }
import akka.annotation.InternalApi
import akka.persistence.typed.NoReplyEffectImpl
import akka.persistence.typed.Stop

/** INTERNAL API */
@InternalApi
private[akka] abstract class EffectImpl[+Event, State] extends javadsl.ReplyEffect[Event, State] with scaladsl.ReplyEffect[Event, State] {
  /* All events that will be persisted in this effect */
  override def events: immutable.Seq[Event] = Nil

  override def andThen(chainedEffect: SideEffect[State]): EffectImpl[Event, State] =
    CompositeEffect(this, chainedEffect)

  override def thenNoReply(): EffectImpl[Event, State] =
    CompositeEffect(this, new NoReplyEffectImpl[State])

  override def thenStop(): EffectImpl[Event, State] =
    CompositeEffect(this, Stop.asInstanceOf[SideEffect[State]])

}

/** INTERNAL API */
@InternalApi
private[akka] object CompositeEffect {
  def apply[Event, State](effect: scaladsl.Effect[Event, State], sideEffects: SideEffect[State]): CompositeEffect[Event, State] =
    CompositeEffect[Event, State](effect, sideEffects :: Nil)
}

/** INTERNAL API */
@InternalApi
private[akka] final case class CompositeEffect[Event, State](
  persistingEffect: scaladsl.Effect[Event, State],
  _sideEffects:     immutable.Seq[SideEffect[State]]) extends EffectImpl[Event, State] {

  override val events: immutable.Seq[Event] = persistingEffect.events

  override def toString: String =
    s"CompositeEffect($persistingEffect, sideEffects: ${_sideEffects.size})"
}

/** INTERNAL API */
@InternalApi
private[akka] case object PersistNothing extends EffectImpl[Nothing, Nothing]

/** INTERNAL API */
@InternalApi
private[akka] case class Persist[Event, State](event: Event) extends EffectImpl[Event, State] {
  override def events = event :: Nil
}

/** INTERNAL API */
@InternalApi
private[akka] case class PersistAll[Event, State](override val events: immutable.Seq[Event]) extends EffectImpl[Event, State]

/** INTERNAL API */
@InternalApi
private[akka] case object Unhandled extends EffectImpl[Nothing, Nothing]


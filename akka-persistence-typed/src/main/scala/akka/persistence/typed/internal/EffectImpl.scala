/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.persistence.typed.javadsl
import akka.persistence.typed.scaladsl

import scala.collection.{ immutable ⇒ im }
import akka.annotation.{ DoNotInherit, InternalApi }

/** INTERNAL API */
@InternalApi
private[akka] abstract class EffectImpl[+Event, State] extends javadsl.Effect[Event, State] with scaladsl.Effect[Event, State] {
  /* All events that will be persisted in this effect */
  override def events: im.Seq[Event] = Nil

  /* All side effects that will be performed in this effect */
  override def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = Nil
}

/** INTERNAL API */
@InternalApi
private[akka] object CompositeEffect {
  def apply[Event, State](effect: EffectImpl[Event, State], sideEffects: ChainableEffect[Event, State]): EffectImpl[Event, State] =
    CompositeEffect[Event, State](effect, sideEffects :: Nil)
}

/** INTERNAL API */
@InternalApi
private[akka] final case class CompositeEffect[Event, State](
  persistingEffect: EffectImpl[Event, State],
  _sideEffects:     im.Seq[ChainableEffect[Event, State]]) extends EffectImpl[Event, State] {

  override val events = persistingEffect.events

  override def sideEffects[E >: Event]: im.Seq[ChainableEffect[E, State]] = _sideEffects.asInstanceOf[im.Seq[ChainableEffect[E, State]]]

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
private[akka] case class PersistAll[Event, State](override val events: im.Seq[Event]) extends EffectImpl[Event, State]

/** INTERNAL API */
@InternalApi
private[akka] case class SideEffect[Event, State](effect: State ⇒ Unit) extends ChainableEffect[Event, State]

/** INTERNAL API */
@InternalApi
private[akka] case object Stop extends ChainableEffect[Nothing, Nothing]

/** INTERNAL API */
@InternalApi
private[akka] case object Unhandled extends EffectImpl[Nothing, Nothing]

/**
 * Not for user extension
 */
@DoNotInherit
abstract class ChainableEffect[Event, State] extends EffectImpl[Event, State]


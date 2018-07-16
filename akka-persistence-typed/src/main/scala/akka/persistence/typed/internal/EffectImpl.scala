/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.persistence.typed.javadsl
import akka.persistence.typed.scaladsl

import scala.collection.{ immutable ⇒ im }
import akka.annotation.InternalApi
import akka.persistence.typed.scaladsl.{ ChainedEffect, Effect }

/** INTERNAL API */
@InternalApi
private[akka] abstract class EffectImpl[+Event, State] extends javadsl.Effect[Event, State] with scaladsl.Effect[Event, State] {
  /* All events that will be persisted in this effect */
  override def events: im.Seq[Event] = Nil
}

/** INTERNAL API */
@InternalApi
private[akka] object CompositeEffect {
  def apply[Event, State](effect: Effect[Event, State], sideEffects: ChainedEffect[State]): EffectImpl[Event, State] =
    CompositeEffect[Event, State](effect, sideEffects :: Nil)
}

/** INTERNAL API */
@InternalApi
private[akka] final case class CompositeEffect[Event, State](
  persistingEffect: Effect[Event, State],
  _chainedEffects:  im.Seq[ChainedEffect[State]]) extends EffectImpl[Event, State] {

  override val events = persistingEffect.events

  override def toString: String =
    s"CompositeEffect($persistingEffect, chainedEffects: ${_chainedEffects.size})"
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
private[akka] case class SideEffect[State](effect: State ⇒ Unit) extends ChainedEffect[State]

/** INTERNAL API */
@InternalApi
private[akka] case object Stop extends ChainedEffect[Nothing]

/** INTERNAL API */
@InternalApi
private[akka] case object Unhandled extends EffectImpl[Nothing, Nothing]


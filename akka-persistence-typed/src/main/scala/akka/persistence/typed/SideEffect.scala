/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.japi.function
import akka.annotation.{ DoNotInherit, InternalApi }

/**
 * A [[SideEffect]] is an side effect that can be chained after a main effect.
 *
 * Persist, none and unhandled are main effects. Then any number of
 * call backs can be added to these effects with `andThen`.
 *
 * Not for user extension
 */
sealed abstract class SideEffect[State]

/** INTERNAL API */
@InternalApi
final private[akka] case class Callback[State](effect: State ⇒ Unit) extends SideEffect[State]

/** INTERNAL API */
@InternalApi
private[akka] case object Stop extends SideEffect[Nothing]

object SideEffect {
  /**
   * Create a ChainedEffect that can be run after Effects
   */
  def apply[State](callback: State ⇒ Unit): SideEffect[State] =
    Callback(callback)

  /**
   * Java API
   *
   * Create a ChainedEffect that can be run after Effects
   */
  def create[State](callback: function.Procedure[State]): SideEffect[State] =
    Callback(s ⇒ callback.apply(s))

  def stop[State](): SideEffect[State] = Stop.asInstanceOf[SideEffect[State]]
}


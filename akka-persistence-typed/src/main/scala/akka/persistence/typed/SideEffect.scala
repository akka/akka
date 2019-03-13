/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.typed.ActorRef
import akka.annotation.InternalApi
import akka.japi.function

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
private[akka] class Callback[State](val sideEffect: State => Unit) extends SideEffect[State] {
  override def toString: String = "Callback"
}

/** INTERNAL API */
@InternalApi
final private[akka] class ReplyEffectImpl[ReplyMessage, State](
    replyTo: ActorRef[ReplyMessage],
    replyWithMessage: State => ReplyMessage)
    extends Callback[State](state => replyTo ! replyWithMessage(state)) {
  override def toString: String = "Reply"
}

/** INTERNAL API */
@InternalApi
final private[akka] class NoReplyEffectImpl[State] extends Callback[State](_ => ()) {
  override def toString: String = "NoReply"
}

/** INTERNAL API */
@InternalApi
private[akka] case object Stop extends SideEffect[Nothing]

/** INTERNAL API */
@InternalApi
private[akka] case object UnstashAll extends SideEffect[Nothing]

object SideEffect {

  /**
   * Create a ChainedEffect that can be run after Effects
   */
  def apply[State](callback: State => Unit): SideEffect[State] =
    new Callback(callback)

  /**
   * Java API
   *
   * Create a ChainedEffect that can be run after Effects
   */
  def create[State](callback: function.Procedure[State]): SideEffect[State] =
    new Callback(s => callback.apply(s))

  def stop[State](): SideEffect[State] = Stop.asInstanceOf[SideEffect[State]]

  /**
   * Unstash the commands that were stashed with `javadsl.EffectFactories.stash` or `scaladsl.Effect.stash`
   */
  def unstashAll[State](): SideEffect[State] = UnstashAll.asInstanceOf[SideEffect[State]]
}

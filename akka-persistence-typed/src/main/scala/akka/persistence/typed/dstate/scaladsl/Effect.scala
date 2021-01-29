/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate.scaladsl

import akka.actor.typed.ActorRef
import akka.annotation.DoNotInherit
import akka.persistence.typed.dstate.internal
import akka.persistence.typed.internal.SideEffect

/**
 * A command handler returns an `Effect` directive that defines the next State to persist.
 *
 * Instances are created through the factories in the [[Effect]] companion object.
 *
 * Not for user extension.
 */
@DoNotInherit
trait Effect[+State]

trait ReplyEffect[+State] extends Effect[State]

trait EffectBuilder[+State] extends Effect[State] {

  /**
   * Run the given callback. Callbacks are run sequentially.
   */
  def thenRun(callback: State => Unit): EffectBuilder[State]

  /** The side effect is to stop the actor */
  def thenStop(): EffectBuilder[State]

  /**
   * Unstash the commands that were stashed with [[Effect.stash]].
   *
   * It's allowed to stash messages while unstashing. Those newly added
   * commands will not be processed by this `unstashAll` effect and have to be unstashed
   * by another `unstashAll`.
   */
  def thenUnstashAll(): Effect[State]

  def thenReply[ReplyMessage](replyTo: ActorRef[ReplyMessage])(
      replyWithMessage: State => ReplyMessage): ReplyEffect[State]

  def thenNoReply(): ReplyEffect[State]
}

/**
 * Factory methods for creating [[Effect]] directives - how an durable state actor reacts on a command.
 */
object Effect {

  /**
   * Insert or update.
   */
  def persist[State](state: State): EffectBuilder[State] =
    internal.Persist(state)

  /**
   * Major difference with respect to EventSourced, a durable state can be deleted
   *
   * A delete must trigger a stop or reload the empty state
   * In any case, it needs to start from scratch.
   */
  def delete[State](): EffectBuilder[State] =
    internal.Delete.asInstanceOf[EffectBuilder[State]]

  def none[State]: EffectBuilder[State] =
    internal.PersistNothing.asInstanceOf[EffectBuilder[State]]

  def unhandled[State]: EffectBuilder[State] =
    internal.Unhandled.asInstanceOf[EffectBuilder[State]]

  /**
   * Stash the current command. Can be unstashed later with [[Effect.unstashAll]].
   * TODO: complete docs
   * Side effects can be chained with `thenRun`
   */
  def stash[Event, State](): ReplyEffect[State] =
    internal.Stash.asInstanceOf[EffectBuilder[State]].thenNoReply()

  /**
   * Unstash the commands that were stashed with [[Effect.stash]].
   *
   * It's allowed to stash messages while unstashing. Those newly added
   * commands will not be processed by this `unstashAll` effect and have to be unstashed
   * by another `unstashAll`.
   *
   * @see [[EffectBuilder.thenUnstashAll]]
   */
  def unstashAll[Event, State](): Effect[State] =
    internal.CompositeEffect(none.asInstanceOf[EffectBuilder[State]], SideEffect.unstashAll[State]())

  /**
   * Send a reply message to the command. The type of the
   * reply message must conform to the type specified by the passed replyTo `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo.tell`.
   *
   * TODO: complete docs
   */
  def reply[ReplyMessage, Event, State](replyTo: ActorRef[ReplyMessage])(
      replyWithMessage: ReplyMessage): ReplyEffect[State] =
    none[State].thenReply[ReplyMessage](replyTo)(_ => replyWithMessage)

  /**
   *  TODO: complete docs
   */
  def noReply[Event, State]: ReplyEffect[State] =
    none.thenNoReply()
}

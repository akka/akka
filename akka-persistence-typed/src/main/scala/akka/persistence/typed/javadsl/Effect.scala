/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import scala.collection.JavaConverters._

import akka.annotation.DoNotInherit
import akka.annotation.InternalApi
import akka.japi.function
import akka.persistence.typed.ExpectingReply
import akka.persistence.typed.SideEffect
import akka.persistence.typed.internal._

/**
 * INTERNAL API: see `class EffectFactories`
 */
@InternalApi private[akka] object EffectFactories extends EffectFactories[Nothing, Nothing]

/**
 * Factory methods for creating [[Effect]] directives - how a persistent actor reacts on a command.
 * Created via [[EventSourcedBehavior.Effect]].
 *
 * Not for user extension
 */
@DoNotInherit sealed class EffectFactories[Event, State] {
  /**
   * Persist a single event
   */
  final def persist(event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist all of a the given events. Each event will be applied through `applyEffect` separately but not until
   * all events has been persisted. If an `afterCallBack` is added through [[Effect#andThen]] that will invoked
   * after all the events has been persisted.
   */
  final def persist(events: java.util.List[Event]): Effect[Event, State] = PersistAll(events.asScala.toVector)

  /**
   * Do not persist anything
   */
  def none(): Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

  /**
   * Stop this persistent actor
   */
  def stop(): Effect[Event, State] = none().thenStop()

  /**
   * This command is not handled, but it is not an error that it isn't.
   */
  def unhandled(): Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]

  /**
   * Stash the current command. Can be unstashed later with `Effect.thenUnstashAll`
   * or `EffectFactories.unstashAll`.
   *
   * Note that the stashed commands are kept in an in-memory buffer, so in case of a crash they will not be
   * processed. They will also be discarded if the actor is restarted (or stopped) due to that an exception was
   * thrown from processing a command or side effect after persisting. The stash buffer is preserved for persist
   * failures if an `onPersistFailure` backoff supervisor strategy is defined.
   *
   * Side effects can be chained with `andThen`.
   */
  def stash(): ReplyEffect[Event, State] =
    Stash.asInstanceOf[Effect[Event, State]].thenNoReply()

  /**
   * Unstash the commands that were stashed with `EffectFactories.stash`.
   *
   * It's allowed to stash messages while unstashing. Those newly added
   * commands will not be processed by this `unstashAll` effect and have to be unstashed
   * by another `unstashAll`.
   *
   * Side effects can be chained with `andThen`, but note that the side effect is run immediately and not after
   * processing all unstashed commands.
   *
   * @see [[Effect.thenUnstashAll]]
   */
  def unstashAll(): Effect[Event, State] =
    none().andThen(SideEffect.unstashAll())

  /**
   * Send a reply message to the command, which implements [[ExpectingReply]]. The type of the
   * reply message must conform to the type specified in [[ExpectingReply.replyTo]] `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo.tell`.
   *
   * It is provided as a convenience (reducing boilerplate) and a way to enforce that replies are not forgotten
   * when the `EventSourcedBehavior` is created with [[EventSourcedBehaviorWithEnforcedReplies]]. When
   * `withEnforcedReplies` is used there will be compilation errors if the returned effect isn't a [[ReplyEffect]].
   * The reply message will be sent also if `withEnforcedReplies` isn't used, but then the compiler will not help
   * finding mistakes.
   */
  def reply[ReplyMessage](cmd: ExpectingReply[ReplyMessage], replyWithMessage: ReplyMessage): ReplyEffect[Event, State] =
    none().thenReply[ReplyMessage](cmd, new function.Function[State, ReplyMessage] {
      override def apply(param: State): ReplyMessage = replyWithMessage
    })

  /**
   * When [[EventSourcedBehaviorWithEnforcedReplies]] is used there will be compilation errors if the returned effect
   * isn't a [[ReplyEffect]]. This `noReply` can be used as a conscious decision that a reply shouldn't be
   * sent for a specific command or the reply will be sent later.
   */
  def noReply(): ReplyEffect[Event, State] =
    none().thenNoReply()
}

/**
 * A command handler returns an `Effect` directive that defines what event or events to persist.
 *
 * Additional side effects can be performed in the callback `andThen`
 *
 * Instances of `Effect` are available through factories [[EventSourcedBehavior.Effect]].
 *
 * Not intended for user extension.
 */
@DoNotInherit abstract class Effect[+Event, State] {
  self: EffectImpl[Event, State] ⇒
  /**
   * Run the given callback. Callbacks are run sequentially.
   *
   * @tparam NewState The type of the state after the event is persisted, when not specified will be the same as `State`
   *                  but if a known subtype of `State` is expected that can be specified instead (preferrably by
   *                  explicitly typing the lambda parameter like so: `thenRun((SubState state) -> { ... })`).
   *                  If the state is not of the expected type an [[java.lang.ClassCastException]] is thrown.
   *
   */
  final def thenRun[NewState <: State](callback: function.Procedure[NewState]): Effect[Event, State] =
    CompositeEffect(this, SideEffect[State](s ⇒ callback.apply(s.asInstanceOf[NewState])))

  /**
   * Run the given callback. Callbacks are run sequentially.
   */
  final def thenRun(callback: function.Effect): Effect[Event, State] =
    CompositeEffect(this, SideEffect[State]((_: State) ⇒ callback.apply()))

  /**
   * Run the given callback after the current Effect
   */
  def andThen(chainedEffect: SideEffect[State]): Effect[Event, State]

  /** The side effect is to stop the actor */
  def thenStop(): Effect[Event, State]

  /**
   * Unstash the commands that were stashed with `EffectFactories.stash`.
   *
   * It's allowed to stash messages while unstashing. Those newly added
   * commands will not be processed by this `unstashAll` effect and have to be unstashed
   * by another `unstashAll`.
   */
  def thenUnstashAll(): Effect[Event, State]

  /**
   * Send a reply message to the command, which implements [[ExpectingReply]]. The type of the
   * reply message must conform to the type specified in [[ExpectingReply.replyTo]] `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo().tell`.
   *
   * It is provided as a convenience (reducing boilerplate) and a way to enforce that replies are not forgotten
   * when the `EventSourcedBehavior` is created with [[EventSourcedBehaviorWithEnforcedReplies]]. When
   * `withEnforcedReplies` is used there will be compilation errors if the returned effect isn't a [[ReplyEffect]].
   * The reply message will be sent also if `withEnforcedReplies` isn't used, but then the compiler will not help
   * finding mistakes.
   */
  def thenReply[ReplyMessage](cmd: ExpectingReply[ReplyMessage], replyWithMessage: function.Function[State, ReplyMessage]): ReplyEffect[Event, State] =
    CompositeEffect(this, SideEffect[State](newState ⇒ cmd.replyTo ! replyWithMessage(newState)))

  /**
   * When [[EventSourcedBehaviorWithEnforcedReplies]] is used there will be compilation errors if the returned effect
   * isn't a [[ReplyEffect]]. This `thenNoReply` can be used as a conscious decision that a reply shouldn't be
   * sent for a specific command or the reply will be sent later.
   */
  def thenNoReply(): ReplyEffect[Event, State]

}

/**
 * [[EventSourcedBehaviorWithEnforcedReplies]] can be used to enforce that replies are not forgotten.
 * Then there will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
 * created with `Effects().reply`, `Effects().noReply`, [[Effect.thenReply]], or [[Effect.thenNoReply]].
 */
@DoNotInherit abstract class ReplyEffect[+Event, State] extends Effect[Event, State] {
  self: EffectImpl[Event, State] ⇒
}

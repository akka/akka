/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import akka.annotation.{ DoNotInherit, InternalApi }
import akka.japi.function
import akka.persistence.typed.internal._
import akka.persistence.typed.SideEffect
import akka.persistence.typed.ExpectingReply

import scala.collection.JavaConverters._

/**
 * INTERNAL API: see `class EffectFactories`
 */
@InternalApi private[akka] object EffectFactories extends EffectFactories[Nothing, Nothing, Nothing]

/**
 * Factory methods for creating [[Effect]] directives - how a persistent actor reacts on a command.
 * Created via [[EventSourcedBehavior.Effect]].
 *
 * Not for user extension
 */
@DoNotInherit sealed class EffectFactories[Command, Event, State] {
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
   * Send a reply message to the command, which implements [[ExpectingReply]]. The type of the
   * reply message must conform to the type specified in [[ExpectingReply.replyTo]] `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo.tell`.
   *
   * It is provided as a convenience (reducing boilerplate) and a way to enforce that replies are not forgotten
   * when the `PersistentBehavior` is created with [[EventSourcedBehaviorWithEnforcedReplies]]. When
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
   */
  final def thenRun(callback: function.Procedure[State]): Effect[Event, State] =
    CompositeEffect(this, SideEffect[State](s ⇒ callback.apply(s)))

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
   * Send a reply message to the command, which implements [[ExpectingReply]]. The type of the
   * reply message must conform to the type specified in [[ExpectingReply.replyTo]] `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo().tell`.
   *
   * It is provided as a convenience (reducing boilerplate) and a way to enforce that replies are not forgotten
   * when the `PersistentBehavior` is created with [[EventSourcedBehaviorWithEnforcedReplies]]. When
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

/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import akka.annotation.DoNotInherit
import akka.japi.function
import akka.persistence.typed.internal._
import akka.persistence.typed.{ SideEffect, Stop }
import scala.collection.JavaConverters._
import akka.persistence.typed.ExpectingReply

/**
 * Factories for effects - how a persistent actor reacts on a command
 */
object Effects {

  /**
   * Persist a single event
   *
   * Side effects can be chained with `andThen`
   */
  def persist[Event, State](event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist multiple events
   *
   * Side effects can be chained with `andThen`
   */
  final def persist[Event, State](events: java.util.List[Event]): Effect[Event, State] = PersistAll(events.asScala.toVector)

  /**
   * Do not persist anything
   *
   * Side effects can be chained with `andThen`
   */
  def none[Event, State]: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

  /**
   * This command is not handled, but it is not an error that it isn't.
   *
   * Side effects can be chained with `andThen`
   */
  def unhandled[Event, State]: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]

  /**
   * Stop this persistent actor
   * Side effects can be chained with `andThen`
   */
  def stop[Event, State]: Effect[Event, State] = none.thenStop()
}

object EffectFactory extends EffectFactories[Nothing, Nothing]

@DoNotInherit sealed class EffectFactories[Event, State] {
  /**
   * Persist a single event.
   *
   * Side effects can be chained with `andThen`.
   */
  final def persist(event: Event): Effect[Event, State] = Persist(event)

  /**
   * Persist multiple events
   *
   * Side effects can be chained with `andThen`.
   */
  final def persist(events: java.util.List[Event]): Effect[Event, State] = PersistAll(events.asScala.toVector)

  /**
   * Do not persist anything
   */
  def none: Effect[Event, State] = PersistNothing.asInstanceOf[Effect[Event, State]]

  /**
   * Stop this persistent actor.
   */
  def stop: Effect[Event, State] = none.thenStop()

  /**
   * This command is not handled, but it is not an error that it isn't.
   */
  def unhandled: Effect[Event, State] = Unhandled.asInstanceOf[Effect[Event, State]]

  /**
   * Send a reply message to the command, which implements [[ExpectingReply]]. The type of the
   * reply message must conform to the type specified in [[ExpectingReply.replyTo]] `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo.tell`.
   *
   * It is provided as a convenience (reducing boilerplate) and a way to enforce that replies are not forgotten
   * when the `PersistentBehavior` is created with [[PersistentBehaviorWithEnforcedReplies]]. When
   * `withEnforcedReplies` is used there will be compilation errors if the returned effect isn't a [[ReplyEffect]].
   * The reply message will be sent also if `withEnforcedReplies` isn't used, but then the compiler will not help
   * finding mistakes.
   */
  def reply[ReplyMessage](cmd: ExpectingReply[ReplyMessage], replyWithMessage: ReplyMessage): ReplyEffect[Event, State] =
    none.thenReply[ReplyMessage](cmd, new function.Function[State, ReplyMessage] {
      override def apply(param: State): ReplyMessage = replyWithMessage
    })

  /**
   * When [[PersistentBehaviorWithEnforcedReplies]] is used there will be compilation errors if the returned effect
   * isn't a [[ReplyEffect]]. This `noReply` can be used as a conscious decision that a reply shouldn't be
   * sent for a specific command or the reply will be sent later.
   */
  def noReply(): ReplyEffect[Event, State] =
    none.thenNoReply()
}

/**
 * A command handler returns an `Effect` directive that defines what event or events to persist.
 *
 * Additional side effects can be performed in the callback `andThen`
 *
 * Instances of `Effect` are available through factories in the respective Java and Scala DSL packages.
 *
 * Not intended for user extension.
 */
@DoNotInherit abstract class Effect[+Event, State] {
  self: EffectImpl[Event, State] ⇒
  /** Convenience method to register a side effect with just a callback function */
  final def andThen(callback: function.Procedure[State]): Effect[Event, State] =
    CompositeEffect(this, SideEffect[State](s ⇒ callback.apply(s)))

  /** Convenience method to register a side effect that doesn't need access to state */
  final def andThen(callback: function.Effect): Effect[Event, State] =
    CompositeEffect(this, SideEffect[State]((_: State) ⇒ callback.apply()))

  def andThen(chainedEffect: SideEffect[State]): Effect[Event, State]

  final def thenStop(): Effect[Event, State] =
    CompositeEffect(this, Stop.asInstanceOf[SideEffect[State]])

  /**
   * Send a reply message to the command, which implements [[ExpectingReply]]. The type of the
   * reply message must conform to the type specified in [[ExpectingReply.replyTo]] `ActorRef`.
   *
   * This has the same semantics as `cmd.replyTo().tell`.
   *
   * It is provided as a convenience (reducing boilerplate) and a way to enforce that replies are not forgotten
   * when the `PersistentBehavior` is created with [[PersistentBehaviorWithEnforcedReplies]]. When
   * `withEnforcedReplies` is used there will be compilation errors if the returned effect isn't a [[ReplyEffect]].
   * The reply message will be sent also if `withEnforcedReplies` isn't used, but then the compiler will not help
   * finding mistakes.
   */
  def thenReply[ReplyMessage](cmd: ExpectingReply[ReplyMessage], replyWithMessage: function.Function[State, ReplyMessage]): ReplyEffect[Event, State] =
    CompositeEffect(this, SideEffect[State](newState ⇒ cmd.replyTo ! replyWithMessage(newState)))

  /**
   * When [[PersistentBehaviorWithEnforcedReplies]] is used there will be compilation errors if the returned effect
   * isn't a [[ReplyEffect]]. This `thenNoReply` can be used as a conscious decision that a reply shouldn't be
   * sent for a specific command or the reply will be sent later.
   */
  def thenNoReply(): ReplyEffect[Event, State]

}

/**
 * [[PersistentBehaviorWithEnforcedReplies]] can be used to enforce that replies are not forgotten.
 * Then there will be compilation errors if the returned effect isn't a [[ReplyEffect]], which can be
 * created with `Effects().reply`, `Effects().noReply`, [[Effect.thenReply]], or [[Effect.thenNoReply]].
 */
@DoNotInherit abstract class ReplyEffect[+Event, State] extends Effect[Event, State] {
  self: EffectImpl[Event, State] ⇒
}

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.dstate.internal

import akka.actor.typed.ActorRef
import akka.annotation.InternalApi
import akka.persistence.typed.dstate.scaladsl
import akka.persistence.typed.internal._

import scala.collection.immutable


  abstract class EffectImpl[State] extends scaladsl.ReplyEffect[State] with scaladsl.EffectBuilder[State] {

    override def thenRun(callback: State => Unit): scaladsl.EffectBuilder[State] =
      CompositeEffect(this, new Callback[State](callback))

    override def thenStop(): EffectImpl[State] =
      CompositeEffect(this, Stop.asInstanceOf[SideEffect[State]])

    override def thenReply[ReplyMessage](replyTo: ActorRef[ReplyMessage])(
      replyWithMessage: State => ReplyMessage): EffectImpl[State] =
      CompositeEffect(this, new ReplyEffectImpl[ReplyMessage, State](replyTo, replyWithMessage))

    override def thenNoReply(): EffectImpl[State] =
      CompositeEffect(this, new NoReplyEffectImpl[State])

    override def thenUnstashAll(): EffectImpl[State] =
      CompositeEffect(this, UnstashAll.asInstanceOf[SideEffect[State]])

  }

  /** INTERNAL API */
  @InternalApi
  private[akka] object CompositeEffect {
    def apply[State](effect: scaladsl.EffectBuilder[State], sideEffects: SideEffect[State]): CompositeEffect[State] =
      CompositeEffect[State](effect, sideEffects :: Nil)
  }

  /** INTERNAL API */
  @InternalApi
  private[akka] final case class CompositeEffect[State](
                                                              persistingEffect: scaladsl.EffectBuilder[State],
                                                              _sideEffects: immutable.Seq[SideEffect[State]])
    extends EffectImpl[State] {

    override def toString: String =
      s"CompositeEffect($persistingEffect, sideEffects: ${_sideEffects.size})"
  }

  /** INTERNAL API */
  @InternalApi
  private[akka] final case class Persist[State](state: State) extends EffectImpl[State]

  /** INTERNAL API */
  @InternalApi
  private[akka] case object Delete extends EffectImpl[Nothing]

  /** INTERNAL API */
  @InternalApi
  private[akka] case object PersistNothing extends EffectImpl[Nothing]

  /** INTERNAL API */
  @InternalApi
  private[akka] case object Unhandled extends EffectImpl[Nothing]

  /** INTERNAL API */
  @InternalApi
  private[akka] case object Stash extends EffectImpl[Nothing]


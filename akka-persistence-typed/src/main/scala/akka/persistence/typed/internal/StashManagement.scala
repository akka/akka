/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.{ actor ⇒ a }
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.actor.{ DeadLetter, StashOverflowException }
import akka.annotation.InternalApi
import akka.persistence._
import akka.util.ConstantFun
import akka.util.OptionVal

/** INTERNAL API: Stash management for persistent behaviors */
@InternalApi
private[akka] trait StashManagement[C, E, S] {
  import akka.actor.typed.scaladsl.adapter._

  def setup: BehaviorSetup[C, E, S]

  private def context: ActorContext[InternalProtocol] = setup.context

  private def stashBuffer: StashBuffer[InternalProtocol] = setup.internalStash

  protected def isStashEmpty: Boolean = stashBuffer.isEmpty

  protected def stash(msg: InternalProtocol): Unit = {
    if (setup.settings.logOnStashing) setup.log.debug("Stashing message: [{}]", msg)

    try stashBuffer.stash(msg) catch {
      case e: StashOverflowException ⇒
        setup.internalStashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val noSenderBecauseAkkaTyped: a.ActorRef = a.ActorRef.noSender
            context.system.deadLetters.tell(DeadLetter(msg, noSenderBecauseAkkaTyped, context.self.toUntyped))

          case ReplyToStrategy(_) ⇒
            throw new RuntimeException("ReplyToStrategy does not make sense at all in Akka Typed, since there is no sender()!")

          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }
  }

  protected def tryUnstash(
    behavior: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    if (stashBuffer.nonEmpty) {
      if (setup.settings.logOnStashing) setup.log.debug("Unstashing message: [{}]", stashBuffer.head.getClass)

      stashBuffer.unstash(setup.context, behavior.asInstanceOf[Behavior[InternalProtocol]], 1, ConstantFun.scalaIdentityFunction)
    } else behavior
  }

}

/**
 * INTERNAL API
 * Main reason for introduction of this trait is stash buffer reference management
 * in order to survive restart of internal behavior
 */
@InternalApi private[akka] trait StashReferenceManagement {

  private var stashBuffer: OptionVal[StashBuffer[InternalProtocol]] = OptionVal.None

  def stashBuffer(settings: EventSourcedSettings): StashBuffer[InternalProtocol] = {
    val buffer: StashBuffer[InternalProtocol] = stashBuffer match {
      case OptionVal.Some(value) ⇒ value
      case _                     ⇒ StashBuffer(settings.stashCapacity)
    }
    this.stashBuffer = OptionVal.Some(buffer)
    stashBuffer.get
  }

  def clearStashBuffer(): Unit = stashBuffer = OptionVal.None
}

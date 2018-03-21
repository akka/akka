/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.actor.{ DeadLetter, StashOverflowException }
import akka.annotation.InternalApi
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence._
import akka.util.ConstantFun
import akka.{ actor ⇒ a }

/** INTERNAL API: Stash management for persistent behaviors */
@InternalApi
private[akka] trait EventsourcedStashManagement[C, E, S] {
  import akka.actor.typed.scaladsl.adapter._

  def setup: EventsourcedSetup[C, E, S]

  private def context: ActorContext[InternalProtocol] = setup.context

  private def stashBuffer: StashBuffer[InternalProtocol] = setup.internalStash

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

/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.persistence.typed.internal

import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.actor.typed.{ Behavior, Logger }
import akka.actor.{ DeadLetter, StashOverflowException }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.EventsourcedProtocol
import akka.util.ConstantFun
import akka.{ actor ⇒ a }

/** INTERNAL API: Stash management for persistent behaviors */
@InternalApi
private[akka] trait EventsourcedStashManagement {
  import akka.actor.typed.scaladsl.adapter._

  protected def log: Logger

  protected def extension: Persistence

  protected val internalStash: StashBuffer[EventsourcedProtocol]

  private lazy val logLevel = {
    val configuredLevel = extension.system.settings.config
      .getString("akka.persistence.typed.log-stashing")
    Logging.levelFor(configuredLevel).getOrElse(Logging.OffLevel)
  }

  /**
   * The returned [[StashOverflowStrategy]] object determines how to handle the message failed to stash
   * when the internal Stash capacity exceeded.
   */
  protected val internalStashOverflowStrategy: StashOverflowStrategy =
    extension.defaultInternalStashOverflowStrategy match {
      case ReplyToStrategy(_) ⇒
        throw new RuntimeException("ReplyToStrategy is not supported in Akka Typed, since there is no sender()!")
      case other ⇒
        other // the other strategies are supported
    }

  protected def stash(ctx: ActorContext[EventsourcedProtocol], msg: EventsourcedProtocol): Unit = {
    if (logLevel != Logging.OffLevel) log.log(logLevel, "Stashing message: {}", msg)

    try internalStash.stash(msg) catch {
      case e: StashOverflowException ⇒
        internalStashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val noSenderBecauseAkkaTyped: a.ActorRef = a.ActorRef.noSender
            ctx.system.deadLetters.tell(DeadLetter(msg, noSenderBecauseAkkaTyped, ctx.self.toUntyped))

          case ReplyToStrategy(response) ⇒
            throw new RuntimeException("ReplyToStrategy does not make sense at all in Akka Typed, since there is no sender()!")

          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }
  }

  protected def tryUnstash(ctx: ActorContext[EventsourcedProtocol], behavior: Behavior[EventsourcedProtocol]): Behavior[EventsourcedProtocol] = {
    if (internalStash.nonEmpty) {
      log.debug("Unstashing message: {}", internalStash.head.getClass)
      internalStash.unstash(ctx, behavior, 1, ConstantFun.scalaIdentityFunction)
    } else behavior
  }

}

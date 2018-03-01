package akka.persistence.typed.internal

import java.util.Locale

import akka.actor.typed.{ ActorSystem, Behavior }
import akka.actor.{ DeadLetter, StashOverflowException }
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.annotation.InternalApi
import akka.event.Logging.LogLevel
import akka.event.{ Logging, LoggingAdapter }
import akka.persistence._
import akka.util.ConstantFun
import akka.{ actor ⇒ a }

/** INTERNAL API: Stash management for persistent behaviors */
@InternalApi
private[akka] trait EventsourcedStashManagement {
  import EventsourcedStashManagement._
  import akka.actor.typed.scaladsl.adapter._

  protected def log: LoggingAdapter

  protected def extension: Persistence

  protected val internalStash: StashBuffer[Any]

  private lazy val logLevel = {
    val configuredLevel = extension.system.settings.config
      .getString("akka.persistence.typed.log-stashing")
    Logging.levelFor(configuredLevel).getOrElse(OffLevel) // this is OffLevel
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

  protected def stash(ctx: ActorContext[Any], msg: Any): Unit = {
    if (logLevel != OffLevel) log.log(logLevel, "Stashing message: {}", msg)

    try internalStash.stash(msg) catch {
      case e: StashOverflowException ⇒
        internalStashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val snd: a.ActorRef = a.ActorRef.noSender // FIXME can we improve it somehow?
            ctx.system.deadLetters.tell(DeadLetter(msg, snd, ctx.self.toUntyped))

          case ReplyToStrategy(response) ⇒
            throw new RuntimeException("ReplyToStrategy does not make sense at all in Akka Typed, since there is no sender()!")

          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }
  }

  protected def tryUnstash(ctx: ActorContext[Any], behavior: Behavior[Any]): Behavior[Any] = {
    if (internalStash.nonEmpty) {
      log.debug("Unstashing message: {}", internalStash.head.getClass)
      internalStash.unstash(ctx, behavior, 1, ConstantFun.scalaIdentityFunction)
    } else behavior
  }

}

object EventsourcedStashManagement {
  private val OffLevel = LogLevel(Int.MinValue)
}

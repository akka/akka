package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{ ActorContext, StashBuffer }
import akka.actor.{ DeadLetter, StashOverflowException }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.{ StashOverflowStrategy, _ }
import akka.util.ConstantFun
import akka.{ actor ⇒ a }

/** INTERNAL API: Stash management for persistent behaviors */
@InternalApi
private[akka] trait EventsourcedStashManagement {
  import akka.actor.typed.scaladsl.adapter._

  protected def stash(setup: EventsourcedSetup[_, _, _], stash: StashBuffer[InternalProtocol], msg: InternalProtocol): Unit = {
    import setup.context

    val logLevel = setup.settings.stashingLogLevel
    if (logLevel != Logging.OffLevel) context.log.debug("Stashing message: {}", msg) // FIXME can be log(logLevel once missing method added

    val internalStashOverflowStrategy: StashOverflowStrategy = setup.persistence.defaultInternalStashOverflowStrategy

    try stash.stash(msg) catch {
      case e: StashOverflowException ⇒
        internalStashOverflowStrategy match {
          case DiscardToDeadLetterStrategy ⇒
            val snd: a.ActorRef = a.ActorRef.noSender // FIXME can we improve it somehow?
            context.system.deadLetters.tell(DeadLetter(msg, snd, context.self.toUntyped))

          case ReplyToStrategy(_) ⇒
            throw new RuntimeException("ReplyToStrategy does not make sense at all in Akka Typed, since there is no sender()!")

          case ThrowOverflowExceptionStrategy ⇒
            throw e
        }
    }
  }

  // FIXME, yet we need to also stash not-commands, due to journal responses ...
  protected def tryUnstash[C, E, S](
    setup:         EventsourcedSetup[C, E, S],
    internalStash: StashBuffer[InternalProtocol], // TODO since may want to not have it inside setup
    behavior:      Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    if (internalStash.nonEmpty) {
      setup.log.debug("Unstashing message: {}", internalStash.head.getClass)

      internalStash.asInstanceOf[StashBuffer[InternalProtocol]].unstash(setup.context, behavior.asInstanceOf[Behavior[InternalProtocol]], 1, ConstantFun.scalaIdentityFunction)
    } else behavior
  }

}

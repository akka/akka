/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol
import akka.persistence.typed.internal.EventsourcedBehavior.MDC

/**
 * INTERNAL API
 *
 * First (of four) behaviour of an PersistentBehaviour.
 *
 * Requests a permit to start replaying this actor; this is tone to avoid
 * hammering the journal with too many concurrently replaying actors.
 *
 * See next behavior [[EventsourcedReplayingSnapshot]].
 */
@InternalApi
private[akka] object EventsourcedRequestingRecoveryPermit {

  def apply[C, E, S](setup: EventsourcedSetup[C, E, S]): Behavior[InternalProtocol] =
    new EventsourcedRequestingRecoveryPermit(setup.setMdc(MDC.AwaitingPermit)).createBehavior()

}

@InternalApi
private[akka] class EventsourcedRequestingRecoveryPermit[C, E, S](override val setup: EventsourcedSetup[C, E, S])
  extends EventsourcedStashManagement[C, E, S] with EventsourcedJournalInteractions[C, E, S] {

  def createBehavior(): Behavior[InternalProtocol] = {
    // request a permit, as only once we obtain one we can start replaying
    requestRecoveryPermit()

    def stay(receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
      Behaviors.receiveMessage[InternalProtocol] {
        case InternalProtocol.RecoveryPermitGranted ⇒
          becomeReplaying(receivedPoisonPill)

        case _ if receivedPoisonPill ⇒
          Behaviors.unhandled

        case other ⇒
          if (receivedPoisonPill) {
            if (setup.settings.logOnStashing) setup.log.debug(
              "Discarding message [{}], because actor is to be stopped", other)
            Behaviors.unhandled
          } else {
            stash(other)
            Behaviors.same
          }

      }.receiveSignal {
        case (_, PoisonPill) ⇒ stay(receivedPoisonPill = true)
      }
    }
    stay(receivedPoisonPill = false)
  }

  private def becomeReplaying(receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
    setup.log.debug(s"Initializing snapshot recovery: {}", setup.recovery)

    setup.holdingRecoveryPermit = true
    EventsourcedReplayingSnapshot(setup, receivedPoisonPill)
  }

}

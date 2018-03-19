/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.{ Behavior, PostStop, PreRestart }
import akka.actor.typed.scaladsl.Behaviors
import akka.annotation.InternalApi
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.InternalProtocol

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
    new EventsourcedRequestingRecoveryPermit(setup).createBehavior()

}

@InternalApi
private[akka] class EventsourcedRequestingRecoveryPermit[C, E, S](override val setup: EventsourcedSetup[C, E, S])
  extends EventsourcedStashManagement[C, E, S] with EventsourcedJournalInteractions[C, E, S] {

  def createBehavior(): Behavior[InternalProtocol] = {
    // request a permit, as only once we obtain one we can start replaying
    requestRecoveryPermit()

    withMdc {
      Behaviors.receive[InternalProtocol] {
        case (_, InternalProtocol.RecoveryPermitGranted) ⇒
          becomeReplaying()

        case (_, other) ⇒
          stash(other)
          Behaviors.same
      }
    }
  }

  private def withMdc(wrapped: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    val mdc = Map(
      "persistenceId" → setup.persistenceId,
      "phase" → "awaiting-permit"
    )

    Behaviors.withMdc(_ ⇒ mdc, wrapped)
  }

  private def becomeReplaying(): Behavior[InternalProtocol] = {
    setup.log.debug(s"Initializing snapshot recovery: {}", setup.recovery)

    setup.holdingRecoveryPermit = true
    EventsourcedReplayingSnapshot(setup)
  }

}

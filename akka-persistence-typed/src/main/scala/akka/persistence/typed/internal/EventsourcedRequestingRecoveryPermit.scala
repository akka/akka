/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
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
 * Requests a permit to start recovering this actor; this is tone to avoid
 * hammering the journal with too many concurrently recovering actors.
 *
 * See next behavior [[EventsourcedRecoveringSnapshot]].
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
    // request a permit, as only once we obtain one we can start recovering
    requestRecoveryPermit()

    withMdc {
      Behaviors.immutable[InternalProtocol] {
        case (_, InternalProtocol.RecoveryPermitGranted) ⇒
          becomeRecovering()

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

  private def becomeRecovering(): Behavior[InternalProtocol] = {
    setup.log.debug(s"Initializing snapshot recovery: {}", setup.recovery)

    setup.holdingRecoveryPermit = true
    EventsourcedRecoveringSnapshot(setup)
  }

}

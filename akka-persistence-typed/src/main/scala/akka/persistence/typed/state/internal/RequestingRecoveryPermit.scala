/*
 * Copyright (C) 2016-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.state.internal

import akka.actor.typed.Behavior
import akka.actor.typed.internal.PoisonPill
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.annotation.{ InternalApi, InternalStableApi }
import akka.util.unused

/**
 * INTERNAL API
 *
 * First (of three) behavior of a `DurableStateBehavior`.
 *
 * Requests a permit to start recovery of this actor; this is tone to avoid
 * hammering the store with too many concurrently recovering actors.
 *
 * See next behavior [[Recovering]].
 */
@InternalApi
private[akka] object RequestingRecoveryPermit {

  def apply[C, S](setup: BehaviorSetup[C, S]): Behavior[InternalProtocol] =
    new RequestingRecoveryPermit(setup.setMdcPhase(PersistenceMdc.AwaitingPermit)).createBehavior()

}

@InternalApi
private[akka] class RequestingRecoveryPermit[C, S](override val setup: BehaviorSetup[C, S])
    extends StashManagement[C, S]
    with DurableStateStoreInteractions[C, S] {

  onRequestingRecoveryPermit(setup.context)

  def createBehavior(): Behavior[InternalProtocol] = {
    // request a permit, as only once we obtain one we can start recovery
    requestRecoveryPermit()

    def stay(receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
      Behaviors
        .receiveMessage[InternalProtocol] {
          case InternalProtocol.RecoveryPermitGranted =>
            becomeRecovering(receivedPoisonPill)

          case other =>
            if (receivedPoisonPill) {
              if (setup.settings.logOnStashing)
                setup.internalLogger.debug("Discarding message [{}], because actor is to be stopped.", other)
              Behaviors.unhandled
            } else {
              stashInternal(other)
            }

        }
        .receiveSignal {
          case (_, PoisonPill) =>
            stay(receivedPoisonPill = true)
          case (_, signal) =>
            if (setup.onSignal(setup.emptyState, signal, catchAndLog = true)) Behaviors.same
            else Behaviors.unhandled
        }
    }
    stay(receivedPoisonPill = false)
  }

  @InternalStableApi
  def onRequestingRecoveryPermit(@unused context: ActorContext[_]): Unit = ()

  private def becomeRecovering(receivedPoisonPill: Boolean): Behavior[InternalProtocol] = {
    setup.internalLogger.debug(s"Initializing recovery")

    setup.holdingRecoveryPermit = true
    Recovering(setup, receivedPoisonPill)
  }

}

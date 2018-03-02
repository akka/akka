/**
 * Copyright (C) 2016-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.persistence.typed.internal

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.scaladsl.{ ActorContext, Behaviors, StashBuffer, TimerScheduler }
import akka.annotation.InternalApi
import akka.event.Logging
import akka.persistence._
import akka.persistence.typed.internal.EventsourcedBehavior.{ InternalProtocol, WriterIdentity }

/**
 * INTERNAL API
 *
 * First (of four) behaviour of an PersistentBehaviour.
 * See next behavior [[EventsourcedRecoveringSnapshot]].
 *
 * Requests a permit to start recovering this actor; this is tone to avoid
 * hammering the journal with too many concurrently recovering actors.
 *
 */
@InternalApi
private[akka] object EventsourcedRequestingRecoveryPermit extends EventsourcedStashManagement {
  import akka.actor.typed.scaladsl.adapter._

  def apply[Command, Event, State](setup: EventsourcedSetup[Command, Event, State]): Behavior[InternalProtocol] = {
    // request a permit, as only once we obtain one we can start recovering
    requestRecoveryPermit(setup.context, setup.persistence)

    withMdc(setup) {
      Behaviors.immutable[InternalProtocol] {
        case (_, InternalProtocol.RecoveryPermitGranted) ⇒ // FIXME types
          becomeRecovering(setup)

        case (_, other) ⇒
          stash(setup, setup.internalStash, other)
          Behaviors.same
      }
    }
  }

  private def withMdc[C, E, S](setup: EventsourcedSetup[C, E, S])(wrapped: Behavior[InternalProtocol]): Behavior[InternalProtocol] = {
    val mdc = Map(
      "persistenceId" → setup.persistenceId,
      "phase" → "awaiting-permit"
    )

    Behaviors.withMdc(_ ⇒ mdc, wrapped)
  }

  private def becomeRecovering[Command, Event, State](setup: EventsourcedSetup[Command, Event, State]): Behavior[InternalProtocol] = {
    setup.log.debug(s"Initializing snapshot recovery: {}", setup.recovery)

    EventsourcedRecoveringSnapshot(setup)
  }

  // ---------- journal interactions ---------

  private def requestRecoveryPermit[Command](context: ActorContext[Command], persistence: Persistence): Unit = {
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    val selfUntyped = context.self.toUntyped
    persistence.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, selfUntyped)
  }

}

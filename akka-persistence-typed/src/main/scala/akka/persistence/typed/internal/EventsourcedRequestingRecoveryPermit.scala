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
import akka.persistence.typed.internal.EventsourcedBehavior.EventsourcedProtocol.IncomingCommand
import akka.persistence.typed.internal.EventsourcedBehavior.{ EventsourcedProtocol, WriterIdentity }

/**
 * INTERNAL API
 *
 * First (of four) behaviour of an PersistentBehaviour.
 *
 * Requests a permit to start recovering this actor; this is tone to avoid
 * hammering the journal with too many concurrently recovering actors.
 */
@InternalApi
private[akka] final class EventsourcedRequestingRecoveryPermit[C, E, S](
  val setup:            EventsourcedSetup[C, E, S],
  override val context: ActorContext[EventsourcedProtocol],
  override val timers:  TimerScheduler[EventsourcedProtocol]
) extends MutableBehavior[EventsourcedProtocol]
  with EventsourcedBehavior[C, E, S]
  with EventsourcedStashManagement {
  import setup._

  import akka.actor.typed.scaladsl.adapter._

  // has to be lazy, since we want to obtain the persistenceId
  protected lazy val log = Logging(context.system.toUntyped, this)

  log.info("created EventsourcedRequestingRecoveryPermit" + this)

  override protected val internalStash: StashBuffer[EventsourcedProtocol] = {
    val stashSize = context.system.settings.config
      .getInt("akka.persistence.typed.stash-buffer-size")
    StashBuffer[EventsourcedProtocol](stashSize)
  }

  // --- initialization ---
  // only once we have a permit, we can become active:
  requestRecoveryPermit()

  val writerIdentity: WriterIdentity = WriterIdentity.newIdentity()

  // --- end of initialization ---

  // ----------

  def becomeRecovering(): Behavior[EventsourcedProtocol] = {
    log.debug(s"Initializing snapshot recovery: {}", recovery)

    new EventsourcedRecoveringSnapshot(
      setup,
      context,
      timers,
      internalStash
    )
  }

  // ----------

  override def onMessage(msg: EventsourcedProtocol): Behavior[EventsourcedProtocol] = {
    msg match {
      case EventsourcedProtocol.RecoveryPermitGranted ⇒
        log.debug("Received recovery permit, initializing recovery")
        becomeRecovering()

      case in: EventsourcedProtocol.IncomingCommand[C] ⇒
        stash(context, in)
        Behaviors.same

      case _ ⇒
        Behaviors.unhandled
    }
  }

  // ---------- journal interactions ---------

  private def requestRecoveryPermit(): Unit = {
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    log.info("extension.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, selfUntyped)")
    extension.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRequestingRecoveryPermit($persistenceId)"
}

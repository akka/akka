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
import akka.persistence.typed.internal.EventsourcedBehavior.WriterIdentity

/**
 * INTERNAL API
 *
 * First (of four) behaviour of an PersistentBehaviour.
 *
 * Requests a permit to start recovering this actor; this is tone to avoid
 * hammering the journal with too many concurrently recovering actors.
 */
@InternalApi
private[akka] final class EventsourcedRequestingRecoveryPermit[Command, Event, State](
  val persistenceId:    String,
  override val context: ActorContext[Any],
  override val timers:  TimerScheduler[Any],

  val recovery: Recovery,

  val callbacks: EventsourcedCallbacks[Command, Event, State],
  val pluginIds: EventsourcedPluginIds
) extends MutableBehavior[Any]
  with EventsourcedBehavior[Command, Event, State]
  with EventsourcedStashManagement {

  import akka.actor.typed.scaladsl.adapter._

  // has to be lazy, since we want to obtain the persistenceId
  protected lazy val log = Logging(context.system.toUntyped, this)

  override protected val internalStash: StashBuffer[Any] = {
    val stashSize = context.system.settings.config
      .getInt("akka.persistence.typed.stash-buffer-size")
    StashBuffer[Any](stashSize)
  }

  // --- initialization ---
  // only once we have a permit, we can become active:
  requestRecoveryPermit()

  val writerIdentity: WriterIdentity = WriterIdentity.newIdentity()

  // --- end of initialization ---

  // ----------

  def becomeRecovering(): Behavior[Any] = {
    log.debug(s"Initializing snapshot recovery: {}", recovery)

    new EventsourcedRecoveringSnapshot(
      persistenceId,
      context,
      timers,
      internalStash,

      recovery,
      writerIdentity,

      callbacks,
      pluginIds
    )
  }

  // ----------

  override def onMessage(msg: Any): Behavior[Any] = {
    msg match {
      case RecoveryPermitter.RecoveryPermitGranted ⇒
        log.debug("Awaiting permit, received: RecoveryPermitGranted")
        becomeRecovering()

      case other ⇒
        stash(context, other)
        Behaviors.same
    }
  }

  // ---------- journal interactions ---------

  private def requestRecoveryPermit(): Unit = {
    // IMPORTANT to use selfUntyped, and not an adapter, since recovery permitter watches/unwatches those refs (and adapters are new refs)
    extension.recoveryPermitter.tell(RecoveryPermitter.RequestRecoveryPermit, selfUntyped)
  }

  override def toString = s"EventsourcedRequestingRecoveryPermit($persistenceId)"
}

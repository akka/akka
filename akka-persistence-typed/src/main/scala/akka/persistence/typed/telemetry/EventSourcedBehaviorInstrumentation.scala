/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.telemetry

import java.util

import akka.util.ccompat.JavaConverters._
import scala.collection.immutable

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Extension
import akka.actor.typed.ExtensionId
import akka.annotation.InternalStableApi
import akka.event.Logging
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalStableApi
object EventSourcedBehaviorInstrumentation {
  type Context = AnyRef
  val EmptyContext: Context = null
}

/**
 * INTERNAL API: Instrumentation SPI for EventSourcedBehavior.
 */
@InternalStableApi
trait EventSourcedBehaviorInstrumentation {
  import EventSourcedBehaviorInstrumentation.Context

  /**
   * Record before a recovery permit is requested.
   *
   * @param actorRef the `ActorRef` for which the recovery permit is about to be requested
   * @return context that will be passed to `afterRequestRecoveryPermit`
   */
  def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): Context

  /**
   * Record after a recovery permit is requested.
   *
   * @param actorRef the `ActorRef` for which the recovery permit is requested
   * @param context  returned by `beforeRequestRecoveryPermit`
   */
  def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: Context): Unit

  /**
   * Record persistence recovery started.
   *
   * @param actorRef the `ActorRef` for which the recovery is started.
   */
  def recoveryStarted(actorRef: ActorRef[_]): Unit

  /**
   * Record persistence recovery done.
   *
   * @param actorRef the `ActorRef` for which the recovery is finished.
   */
  def recoveryDone(actorRef: ActorRef[_]): Unit

  /**
   * Record persistence recovery failure.
   *
   * @param actorRef  the `ActorRef` for which the recovery has failed.
   * @param throwable the cause of the failure.
   * @param event     the event that was replayed, if any
   */
  def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable, event: OptionVal[Any]): Unit

  /**
   * Record persist event.
   *
   * @param actorRef        the `ActorRef` for which the event will be sent to the journal.
   * @param event           the event that was submitted for persistence. For persist of several events it will be
   *                        called for each event in the batch in the same order.
   * @param command         actor message (command), if any, for which the event was emitted.
   * @return context that will be passed to `persistEventWritten`
   */
  def persistEventCalled(actorRef: ActorRef[_], event: Any, command: OptionVal[Any]): Context

  /**
   * Record event is written but the registered callback has not been called yet
   *
   * @param actorRef     the `ActorRef` for which the event has been successfully persisted.
   * @param event        the event that was stored in the journal.
   * @param context context returned by `persistEventCalled`
   * @return context that will be passed to `persistEventDone`
   */
  def persistEventWritten(actorRef: ActorRef[_], event: Any, context: Context): Context

  /**
   * Record event is written and the registered callback is called. When more than one event is
   * persisted this will only be called once when the last event has been completed.
   *
   * @param actorRef     the `ActorRef` for which the event has been successfully persisted.
   * @param context context returned by `persistEventWritten`
   */
  def persistEventDone(actorRef: ActorRef[_], context: Context): Unit

  /**
   * Record persistence persist failure.
   *
   * @param actorRef     the `ActorRef` for which the recovery has failed.
   * @param throwable    the cause of the failure.
   * @param event        the event that was to be persisted.
   * @param seqNr        the sequence number associated with the failure
   * @param context context returned by `persistEventCalled`
   */
  def persistFailed(actorRef: ActorRef[_], throwable: Throwable, event: Any, seqNr: Long, context: Context): Unit

  /**
   * Record persistence persist failure.
   *
   * @param actorRef     the `ActorRef` for which the recovery has failed.
   * @param throwable    the cause of the failure.
   * @param event        the event that was to be persisted.
   * @param seqNr        the sequence number associated with the failure
   * @param context context returned by `persistEventCalled`
   */
  def persistRejected(actorRef: ActorRef[_], throwable: Throwable, event: Any, seqNr: Long, context: Context): Unit
}

/**
 * INTERNAL API
 */
@InternalStableApi
object EmptyEventSourcedBehaviorInstrumentation extends EventSourcedBehaviorInstrumentation {
  import EventSourcedBehaviorInstrumentation.{ Context, EmptyContext }

  override def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): Context = EmptyContext

  override def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: Context): Unit = ()

  override def recoveryStarted(actorRef: ActorRef[_]): Unit = ()

  override def recoveryDone(actorRef: ActorRef[_]): Unit = ()

  override def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable, event: OptionVal[Any]): Unit = ()

  override def persistEventCalled(actorRef: ActorRef[_], event: Any, command: OptionVal[Any]): Context = EmptyContext

  override def persistEventWritten(actorRef: ActorRef[_], event: Any, context: Context): Context = EmptyContext

  override def persistEventDone(actorRef: ActorRef[_], context: Context): Unit = ()

  override def persistFailed(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: Any,
      seqNr: Long,
      context: Context): Unit = ()

  override def persistRejected(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: Any,
      seqNr: Long,
      context: Context): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalStableApi
class EnsembleEventSourcedBehaviorInstrumentation(instrumentations: Seq[EventSourcedBehaviorInstrumentation])
    extends EventSourcedBehaviorInstrumentation {
  import EventSourcedBehaviorInstrumentation.Context

  override def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): Context =
    instrumentations.map(_.beforeRequestRecoveryPermit(actorRef))

  override def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: Context): Unit =
    instrumentations.foreach(_.afterRequestRecoveryPermit(actorRef, context))

  override def recoveryStarted(actorRef: ActorRef[_]): Unit =
    instrumentations.foreach(_.recoveryStarted(actorRef))

  override def recoveryDone(actorRef: ActorRef[_]): Unit =
    instrumentations.foreach(_.recoveryDone(actorRef))

  override def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable, event: OptionVal[Any]): Unit =
    instrumentations.foreach(_.recoveryFailed(actorRef, throwable, event))

  override def persistEventCalled(actorRef: ActorRef[_], event: Any, command: OptionVal[Any]): Context =
    instrumentations.map(_.persistEventCalled(actorRef, event, command))

  override def persistEventWritten(actorRef: ActorRef[_], event: Any, context: Context): Context = {
    val contexts = context.asInstanceOf[Seq[Context]]
    contexts.zip(instrumentations).map {
      case (ctx, instrumentation) => instrumentation.persistEventWritten(actorRef, event, ctx)
    }
  }

  override def persistEventDone(actorRef: ActorRef[_], context: Context): Unit =
    instrumentations.foreach(_.persistEventDone(actorRef, context))

  override def persistFailed(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: Any,
      seqNr: Long,
      context: Context): Unit =
    instrumentations.foreach(_.persistFailed(actorRef, throwable, event, seqNr, context))

  override def persistRejected(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: Any,
      seqNr: Long,
      context: Context): Unit =
    instrumentations.foreach(_.persistRejected(actorRef, throwable, event, seqNr, context))
}

/**
 * INTERNAL API
 */
@InternalStableApi
object EventSourcedBehaviorInstrumentationProvider extends ExtensionId[EventSourcedBehaviorInstrumentationProvider] {
  def createExtension(system: ActorSystem[_]): EventSourcedBehaviorInstrumentationProvider =
    new EventSourcedBehaviorInstrumentationProvider(system)
  def get(system: ActorSystem[_]): EventSourcedBehaviorInstrumentationProvider = apply(system)
}

/**
 * INTERNAL API
 */
@InternalStableApi
class EventSourcedBehaviorInstrumentationProvider(system: ActorSystem[_]) extends Extension {
  private val fqcnConfigPath = "akka.persistence.typed.telemetry.event-sourced-behavior.instrumentations"

  val instrumentation: EventSourcedBehaviorInstrumentation = {
    if (!system.settings.config.hasPath(fqcnConfigPath)) {
      EmptyEventSourcedBehaviorInstrumentation
    } else {
      val fqcns: util.List[String] = system.settings.config.getStringList(fqcnConfigPath)

      fqcns.size() match {
        case 0 => EmptyEventSourcedBehaviorInstrumentation
        case 1 => create(fqcns.get(0))
        case _ =>
          val instrumentations = fqcns.asScala.map(fqcn => create(fqcn)).toVector
          new EnsembleEventSourcedBehaviorInstrumentation(instrumentations)
      }
    }
  }

  private def create(fqcn: String): EventSourcedBehaviorInstrumentation = {
    try {
      system.dynamicAccess
        .createInstanceFor[EventSourcedBehaviorInstrumentation](fqcn, immutable.Seq((classOf[ActorSystem[_]], system)))
        .get
    } catch {
      case t: Throwable => // Throwable, because instrumentation failure should not cause fatal shutdown
        Logging(system.classicSystem, getClass).warning(t, "Cannot create instrumentation [{}]", fqcn)
        EmptyEventSourcedBehaviorInstrumentation
    }
  }
}

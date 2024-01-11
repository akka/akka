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
object DurableStateBehaviorInstrumentation {
  type Context = AnyRef
  val EmptyContext: Context = null
}

/**
 * INTERNAL API: Instrumentation SPI for DurableStateBehavior.
 */
@InternalStableApi
trait DurableStateBehaviorInstrumentation {
  import DurableStateBehaviorInstrumentation.Context

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
   */
  def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable): Unit

  /**
   * Record persist state.
   *
   * @param actorRef        the `ActorRef` for which the state will be sent to the store.
   * @param state           the state that was submitted for persistence.
   * @param command         actor message (command), if any, for which the state update was emitted.
   * @return context that will be passed to `persistStateWritten`
   */
  def persistStateCalled(actorRef: ActorRef[_], state: Any, command: OptionVal[Any]): Context

  /**
   * Record delete state.
   *
   * @param actorRef        the `ActorRef` for which the delete state will be sent to the store.
   * @param command         actor message (command), if any, for which the state update was emitted.
   * @return context that will be passed to `persistStateWritten`
   */
  def deleteStateCalled(actorRef: ActorRef[_], command: OptionVal[Any]): Context

  /**
   * Record state is written but the registered callback has not been called yet
   *
   * @param actorRef     the `ActorRef` for which the state has been successfully persisted.
   * @param state        the state that was stored in the journal.
   * @param context context returned by `persistStateCalled`
   * @return context that will be passed to `persistStateDone`
   */
  def persistStateWritten(actorRef: ActorRef[_], state: Any, context: Context): Context

  /**
   * Record state is written and the registered callback is called.
   *
   * @param actorRef     the `ActorRef` for which the state has been successfully persisted.
   * @param context context returned by `persistStateWritten`
   */
  def persistStateDone(actorRef: ActorRef[_], context: Context): Unit

  /**
   * Record persistence persist failure.
   *
   * @param actorRef     the `ActorRef` for which the recovery has failed.
   * @param throwable    the cause of the failure.
   * @param state        the state that was to be persisted.
   * @param revision        the sequence number associated with the failure
   * @param context context returned by `persistStateCalled`
   */
  def persistFailed(actorRef: ActorRef[_], throwable: Throwable, state: Any, revision: Long, context: Context): Unit

}

/**
 * INTERNAL API
 */
@InternalStableApi
object EmptyDurableStateBehaviorInstrumentation extends DurableStateBehaviorInstrumentation {
  import DurableStateBehaviorInstrumentation.{ Context, EmptyContext }

  override def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): Context = EmptyContext

  override def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: Context): Unit = ()

  override def recoveryStarted(actorRef: ActorRef[_]): Unit = ()

  override def recoveryDone(actorRef: ActorRef[_]): Unit = ()

  override def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable): Unit = ()

  override def persistStateCalled(actorRef: ActorRef[_], state: Any, command: OptionVal[Any]): Context = EmptyContext

  override def deleteStateCalled(actorRef: ActorRef[_], command: OptionVal[Any]): Context = EmptyContext

  override def persistStateWritten(actorRef: ActorRef[_], state: Any, context: Context): Context = EmptyContext

  override def persistStateDone(actorRef: ActorRef[_], context: Context): Unit = ()

  override def persistFailed(
      actorRef: ActorRef[_],
      throwable: Throwable,
      state: Any,
      revision: Long,
      context: Context): Unit = ()

}

/**
 * INTERNAL API
 */
@InternalStableApi
class EnsembleDurableStateBehaviorInstrumentation(instrumentations: Seq[DurableStateBehaviorInstrumentation])
    extends DurableStateBehaviorInstrumentation {
  import DurableStateBehaviorInstrumentation.Context

  override def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): Context =
    instrumentations.map(_.beforeRequestRecoveryPermit(actorRef))

  override def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: Context): Unit =
    instrumentations.foreach(_.afterRequestRecoveryPermit(actorRef, context))

  override def recoveryStarted(actorRef: ActorRef[_]): Unit =
    instrumentations.foreach(_.recoveryStarted(actorRef))

  override def recoveryDone(actorRef: ActorRef[_]): Unit =
    instrumentations.foreach(_.recoveryDone(actorRef))

  override def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable): Unit =
    instrumentations.foreach(_.recoveryFailed(actorRef, throwable))

  override def persistStateCalled(actorRef: ActorRef[_], state: Any, command: OptionVal[Any]): Context =
    instrumentations.map(_.persistStateCalled(actorRef, state, command))

  override def deleteStateCalled(actorRef: ActorRef[_], command: OptionVal[Any]): Context =
    instrumentations.map(_.deleteStateCalled(actorRef, command))

  override def persistStateWritten(actorRef: ActorRef[_], state: Any, context: Context): Context = {
    val contexts = context.asInstanceOf[Seq[Context]]
    contexts.zip(instrumentations).map {
      case (ctx, instrumentation) => instrumentation.persistStateWritten(actorRef, state, ctx)
    }
  }

  override def persistStateDone(actorRef: ActorRef[_], context: Context): Unit =
    instrumentations.foreach(_.persistStateDone(actorRef, context))

  override def persistFailed(
      actorRef: ActorRef[_],
      throwable: Throwable,
      state: Any,
      revision: Long,
      context: Context): Unit =
    instrumentations.foreach(_.persistFailed(actorRef, throwable, state, revision, context))

}

/**
 * INTERNAL API
 */
@InternalStableApi
object DurableStateBehaviorInstrumentationProvider extends ExtensionId[DurableStateBehaviorInstrumentationProvider] {
  def createExtension(system: ActorSystem[_]): DurableStateBehaviorInstrumentationProvider =
    new DurableStateBehaviorInstrumentationProvider(system)
  def get(system: ActorSystem[_]): DurableStateBehaviorInstrumentationProvider = apply(system)
}

/**
 * INTERNAL API
 */
@InternalStableApi
class DurableStateBehaviorInstrumentationProvider(system: ActorSystem[_]) extends Extension {
  private val fqcnConfigPath = "akka.persistence.typed.telemetry.durable-state-behavior.instrumentations"

  val instrumentation: DurableStateBehaviorInstrumentation = {
    if (!system.settings.config.hasPath(fqcnConfigPath)) {
      EmptyDurableStateBehaviorInstrumentation
    } else {
      val fqcns: util.List[String] = system.settings.config.getStringList(fqcnConfigPath)

      fqcns.size() match {
        case 0 => EmptyDurableStateBehaviorInstrumentation
        case 1 => create(fqcns.get(0))
        case _ =>
          val instrumentations = fqcns.asScala.map(fqcn => create(fqcn)).toVector
          new EnsembleDurableStateBehaviorInstrumentation(instrumentations)
      }
    }
  }

  private def create(fqcn: String): DurableStateBehaviorInstrumentation = {
    try {
      system.dynamicAccess
        .createInstanceFor[DurableStateBehaviorInstrumentation](fqcn, immutable.Seq((classOf[ActorSystem[_]], system)))
        .get
    } catch {
      case t: Throwable => // Throwable, because instrumentation failure should not cause fatal shutdown
        Logging(system.classicSystem, getClass).warning(t, "Cannot create instrumentation [{}]", fqcn)
        EmptyDurableStateBehaviorInstrumentation
    }
  }
}

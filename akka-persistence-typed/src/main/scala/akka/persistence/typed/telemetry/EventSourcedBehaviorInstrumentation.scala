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
import akka.event.Logging

object EventSourcedBehaviorInstrumentation {
  val EmptyContext: AnyRef = null
}

/**
 * Instrumentation SPI for EventSourcedBehavior.
 */
trait EventSourcedBehaviorInstrumentation {
  def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): AnyRef
  def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: AnyRef): Unit
  def recoveryStarted(actorRef: ActorRef[_]): Unit
  def recoveryDone(actorRef: ActorRef[_]): Unit
  def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable, event: AnyRef): Unit
  def recoveryPermitterStatus(
      recoveryPermitter: ActorRef[_],
      maxPermits: Int,
      usedPermits: Int,
      pendingActors: Int): Unit
  def persistEventCalled(actorRef: ActorRef[_], event: AnyRef, commandEnvelope: AnyRef): AnyRef
  def persistEventWritten(actorRef: ActorRef[_], event: AnyRef, eventContext: AnyRef): AnyRef
  def persistEventDone(actorRef: ActorRef[_], event: AnyRef, eventContext: AnyRef): Unit
  def persistFailed(actorRef: ActorRef[_], throwable: Throwable, event: AnyRef, seqNr: Long, eventContext: AnyRef): Unit
  def persistRejected(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: AnyRef,
      seqNr: Long,
      eventContext: AnyRef): Unit
}

object EmptyEventSourcedBehaviorInstrumentation extends EventSourcedBehaviorInstrumentation {
  import EventSourcedBehaviorInstrumentation.EmptyContext
  override def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): AnyRef = EmptyContext

  override def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: AnyRef): Unit = ()

  override def recoveryStarted(actorRef: ActorRef[_]): Unit = ()

  override def recoveryDone(actorRef: ActorRef[_]): Unit = ()

  override def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable, event: AnyRef): Unit = ()

  override def recoveryPermitterStatus(
      recoveryPermitter: ActorRef[_],
      maxPermits: Int,
      usedPermits: Int,
      pendingActors: Int): Unit = ()

  override def persistEventCalled(actorRef: ActorRef[_], event: AnyRef, commandEnvelope: AnyRef): AnyRef = EmptyContext

  override def persistEventWritten(actorRef: ActorRef[_], event: AnyRef, eventContext: AnyRef): AnyRef = EmptyContext

  override def persistEventDone(actorRef: ActorRef[_], event: AnyRef, eventContext: AnyRef): Unit = ()

  override def persistFailed(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: AnyRef,
      seqNr: Long,
      eventContext: AnyRef): Unit = ()

  override def persistRejected(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: AnyRef,
      seqNr: Long,
      eventContext: AnyRef): Unit = ()
}

class EnsembleEventSourcedBehaviorInstrumentation(instrumentations: Seq[EventSourcedBehaviorInstrumentation])
    extends EventSourcedBehaviorInstrumentation {

  override def beforeRequestRecoveryPermit(actorRef: ActorRef[_]): AnyRef =
    instrumentations.map(_.beforeRequestRecoveryPermit(actorRef))

  override def afterRequestRecoveryPermit(actorRef: ActorRef[_], context: AnyRef): Unit =
    instrumentations.foreach(_.afterRequestRecoveryPermit(actorRef, context))

  override def recoveryStarted(actorRef: ActorRef[_]): Unit =
    instrumentations.foreach(_.recoveryStarted(actorRef))

  override def recoveryDone(actorRef: ActorRef[_]): Unit =
    instrumentations.foreach(_.recoveryDone(actorRef))

  override def recoveryFailed(actorRef: ActorRef[_], throwable: Throwable, event: AnyRef): Unit =
    instrumentations.foreach(_.recoveryFailed(actorRef, throwable, event))

  override def recoveryPermitterStatus(
      recoveryPermitter: ActorRef[_],
      maxPermits: Int,
      usedPermits: Int,
      pendingActors: Int): Unit =
    instrumentations.foreach(_.recoveryPermitterStatus(recoveryPermitter, maxPermits, usedPermits, pendingActors))

  override def persistEventCalled(actorRef: ActorRef[_], event: AnyRef, commandEnvelope: AnyRef): AnyRef =
    instrumentations.map(_.persistEventCalled(actorRef, event, commandEnvelope))

  override def persistEventWritten(actorRef: ActorRef[_], event: AnyRef, eventContext: AnyRef): AnyRef = {
    val contexts = eventContext.asInstanceOf[Seq[AnyRef]]
    contexts.zip(instrumentations).map {
      case (ctx, instrumentation) => instrumentation.persistEventWritten(actorRef, event, ctx)
    }
  }

  override def persistEventDone(actorRef: ActorRef[_], event: AnyRef, eventContext: AnyRef): Unit =
    instrumentations.foreach(_.persistEventDone(actorRef, event, eventContext))

  override def persistFailed(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: AnyRef,
      seqNr: Long,
      eventContext: AnyRef): Unit =
    instrumentations.foreach(_.persistFailed(actorRef, throwable, event, seqNr, eventContext))

  override def persistRejected(
      actorRef: ActorRef[_],
      throwable: Throwable,
      event: AnyRef,
      seqNr: Long,
      eventContext: AnyRef): Unit =
    instrumentations.foreach(_.persistRejected(actorRef, throwable, event, seqNr, eventContext))
}

object EventSourcedBehaviorInstrumentationProvider extends ExtensionId[EventSourcedBehaviorInstrumentationProvider] {
  def createExtension(system: ActorSystem[_]): EventSourcedBehaviorInstrumentationProvider =
    new EventSourcedBehaviorInstrumentationProvider(system)
  def get(system: ActorSystem[_]): EventSourcedBehaviorInstrumentationProvider = apply(system)
}

class EventSourcedBehaviorInstrumentationProvider(system: ActorSystem[_]) extends Extension {
  private val fqcnConfigPath = "akka.persistence.typed.telemetry.event-sourced-behavior.instrumentations"

  val instrumentation: EventSourcedBehaviorInstrumentation = {
    if (!system.settings.config.hasPath(fqcnConfigPath)) {
      EmptyEventSourcedBehaviorInstrumentation
    } else {
      val fqcns: util.List[String] = system.settings.config.getStringList(fqcnConfigPath)

      fqcns.size() match {
        case 0 =>
          EmptyEventSourcedBehaviorInstrumentation
        case 1 =>
          val fqcn = fqcns.get(0)
          create(fqcn)
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

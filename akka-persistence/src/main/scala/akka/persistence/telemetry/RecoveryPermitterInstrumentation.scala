/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.telemetry

import java.util

import scala.collection.immutable

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ClassicActorSystemProvider
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.annotation.InternalStableApi
import akka.event.Logging
import akka.util.ccompat.JavaConverters._

/**
 * INTERNAL API: Instrumentation SPI for PersistentActor.
 */
@InternalStableApi
trait RecoveryPermitterInstrumentation {

  /**
   * Record recovery permitter status - invoked after an actor has requested a permit.
   *
   * @param recoveryPermitter `ActorRef` handling the permits for this actor
   *                          system.
   * @param maxPermits        the max permits set (via configuration).
   * @param usedPermits       the number of used (issued) permits.
   * @param pendingActors     number of pending actors waiting for a permit.
   */
  def recoveryPermitterStatus(recoveryPermitter: ActorRef, maxPermits: Int, usedPermits: Int, pendingActors: Int): Unit
}

/**
 * INTERNAL API
 */
@InternalStableApi
object EmptyRecoveryPermitterInstrumentation extends RecoveryPermitterInstrumentation {

  override def recoveryPermitterStatus(
      recoveryPermitter: ActorRef,
      maxPermits: Int,
      usedPermits: Int,
      pendingActors: Int): Unit = ()
}

/**
 * INTERNAL API
 */
@InternalStableApi
class EnsembleRecoveryPermitterInstrumentation(instrumentations: Seq[RecoveryPermitterInstrumentation])
    extends RecoveryPermitterInstrumentation {

  override def recoveryPermitterStatus(
      recoveryPermitter: ActorRef,
      maxPermits: Int,
      usedPermits: Int,
      pendingActors: Int): Unit =
    instrumentations.foreach(_.recoveryPermitterStatus(recoveryPermitter, maxPermits, usedPermits, pendingActors))
}

/**
 * INTERNAL API
 */
@InternalStableApi
object RecoveryPermitterInstrumentationProvider
    extends ExtensionId[RecoveryPermitterInstrumentationProvider]
    with ExtensionIdProvider {
  override def get(system: ActorSystem): RecoveryPermitterInstrumentationProvider = super.get(system)

  override def get(system: ClassicActorSystemProvider): RecoveryPermitterInstrumentationProvider = super.get(system)

  override def lookup = RecoveryPermitterInstrumentationProvider

  override def createExtension(system: ExtendedActorSystem): RecoveryPermitterInstrumentationProvider =
    new RecoveryPermitterInstrumentationProvider(system)
}

/**
 * INTERNAL API
 */
@InternalStableApi
class RecoveryPermitterInstrumentationProvider(system: ExtendedActorSystem) extends Extension {
  private val fqcnConfigPath = "akka.persistence.telemetry.recovery-permitter.instrumentations"

  val instrumentation: RecoveryPermitterInstrumentation = {
    if (!system.settings.config.hasPath(fqcnConfigPath)) {
      EmptyRecoveryPermitterInstrumentation
    } else {
      val fqcns: util.List[String] = system.settings.config.getStringList(fqcnConfigPath)

      fqcns.size() match {
        case 0 => EmptyRecoveryPermitterInstrumentation
        case 1 => create(fqcns.get(0))
        case _ =>
          val instrumentations = fqcns.asScala.map(fqcn => create(fqcn)).toVector
          new EnsembleRecoveryPermitterInstrumentation(instrumentations)
      }
    }
  }

  private def create(fqcn: String): RecoveryPermitterInstrumentation = {
    try {
      system.dynamicAccess
        .createInstanceFor[RecoveryPermitterInstrumentation](fqcn, immutable.Seq((classOf[ActorSystem], system)))
        .get
    } catch {
      case t: Throwable => // Throwable, because instrumentation failure should not cause fatal shutdown
        Logging(system.classicSystem, getClass).warning(t, "Cannot create instrumentation [{}]", fqcn)
        EmptyRecoveryPermitterInstrumentation
    }
  }
}

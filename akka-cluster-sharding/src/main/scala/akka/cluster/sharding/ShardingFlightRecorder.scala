/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding
import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.util.JavaVersion

import scala.util.{ Failure, Success }

/**
 * INTERNAL API
 */
@InternalApi
object ShardingFlightRecorder extends ExtensionId[ShardingFlightRecorder] with ExtensionIdProvider {

  override def lookup(): ExtensionId[_ <: Extension] = this

  override def createExtension(system: ExtendedActorSystem): ShardingFlightRecorder =
    if (JavaVersion.majorVersion >= 11 && system.settings.config.getBoolean("akka.java-flight-recorder.enabled")) {
      // Dynamic instantiation to not trigger class load on earlier JDKs
      system.dynamicAccess.createInstanceFor[ShardingFlightRecorder](
        "akka.cluster.sharding.internal.jfr.JFRShardingFlightRecorder",
        Nil) match {
        case Success(jfr) =>
          // FIXME remove
          system.log.info("Created sharding flight recorder")
          jfr
        case Failure(ex) =>
          system.log
            .warning("Failed to load JFR Actor flight recorder, falling back to noop. Exception: {}", ex.toString)
          NoOpShardingFlightRecorder
      } // fallback if not possible to dynamically load for some reason
    } else
      // JFR not available on Java 8
      NoOpShardingFlightRecorder
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] trait ShardingFlightRecorder extends Extension {
  def shardingBlockedOnRememberedEntities(duration: Long): Unit
  def shardingRememberedEntityStore(): Unit
}

/**
 * INTERNAL
 */
@InternalApi
private[akka] case object NoOpShardingFlightRecorder extends ShardingFlightRecorder {
  override def shardingBlockedOnRememberedEntities(duration: Long): Unit = ()
  override def shardingRememberedEntityStore(): Unit = ()
}

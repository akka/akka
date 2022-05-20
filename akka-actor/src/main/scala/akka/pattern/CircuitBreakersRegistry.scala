/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern

import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.duration.{ DurationLong, MILLISECONDS }

import akka.actor.{ ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.pattern.internal.CircuitBreakerTelemetryProvider
import akka.util.ccompat.JavaConverters._

/**
 * Companion object providing factory methods for Circuit Breaker which runs callbacks in caller's thread
 */
object CircuitBreakersRegistry extends ExtensionId[CircuitBreakersRegistry] with ExtensionIdProvider {

  /**
   * Is used by Akka to instantiate the Extension identified by this ExtensionId,
   * internal use only.
   */
  override def createExtension(system: ExtendedActorSystem): CircuitBreakersRegistry =
    new CircuitBreakersRegistry(system)

  /**
   * Returns the canonical ExtensionId for this Extension
   */
  override def lookup: ExtensionId[_ <: Extension] = CircuitBreakersRegistry
}

/**
 * A CircuitBreakersPanel is a central point collecting all circuit breakers in Akka.
 */
final class CircuitBreakersRegistry(system: ExtendedActorSystem) extends Extension {

  private val breakers = new ConcurrentHashMap[String, CircuitBreaker]

  private val config = system.settings.config.getConfig("akka.circuit-breaker")
  private val defaultBreakerConfig = config.getConfig("default")

  private def createCircuitBreaker(id: String): CircuitBreaker = {
    val breakerConfig =
      if (config.hasPath(id)) config.getConfig(id).withFallback(defaultBreakerConfig)
      else defaultBreakerConfig

    // TODO: add configuration breakerConfig.getBoolean("enabled")
    val maxFailures = breakerConfig.getInt("max-failures")
    val callTimeout = breakerConfig.getDuration("call-timeout", MILLISECONDS).millis
    val resetTimeout = breakerConfig.getDuration("reset-timeout", MILLISECONDS).millis

    val allowExceptions: Set[String] = breakerConfig.getStringList("exception-allowlist").asScala.toSet

    val telemetry = CircuitBreakerTelemetryProvider.start(id, system)
    new CircuitBreaker(
      system.scheduler,
      maxFailures,
      callTimeout,
      resetTimeout,
      maxResetTimeout = 36500.days, // TODO: add configuration
      exponentialBackoffFactor = 1.0, // TODO: add configuration
      randomFactor = 0.0, // TODO: add configuration
      allowExceptions,
      telemetry)(system.dispatcher)
  }

  private[akka] def get(id: String): CircuitBreaker =
    breakers.computeIfAbsent(id, createCircuitBreaker)
}

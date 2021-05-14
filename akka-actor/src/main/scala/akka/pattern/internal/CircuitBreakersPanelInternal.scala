/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.pattern.internal

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.pattern.{ CircuitBreaker, CircuitBreakerOpenException }
import com.typesafe.config.Config

import java.util.concurrent.{ CompletionException, ConcurrentHashMap }
import scala.concurrent.duration.{ DurationLong, MILLISECONDS }
import scala.concurrent.{ Future, TimeoutException }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class CircuitBreakersPanelInternal(system: ExtendedActorSystem) {

  private case class CircuitBreakerHolder(
      breaker: CircuitBreaker,
      telemetry: CircuitBreakerTelemetry,
      failureFn: Try[_] => Boolean)

  private class CircuitBreakerConfig(val _config: Config) {
    val config: Config = _config.getConfig("akka.circuit-breaker")
    val default: Config = config.getConfig("default")
  }

  private val breakers = new ConcurrentHashMap[String, Option[CircuitBreakerHolder]]

  private lazy val circuitBreakerConfig: CircuitBreakerConfig = new CircuitBreakerConfig(system.settings.config)
  private lazy val config = circuitBreakerConfig.config
  private lazy val defaultBreakerConfig = circuitBreakerConfig.default

  private val allExceptionAsFailure: Try[_] => Boolean = {
    case _: Success[_] => false
    case _             => true
  }

  private def createCircuitBreaker(id: String): Option[CircuitBreakerHolder] = {

    def ignoredException(ex: Any, whitelist: Set[String]): Boolean = ex match {
      case ce: CompletionException => ce.getCause != null && whitelist.contains(ce.getCause.getClass.getName)
      case _                       => whitelist.contains(ex.getClass.getName)
    }

    def failureDefinition(whitelist: Set[String]): Try[_] => Boolean = {
      case _: Success[_]                                => false
      case Failure(t) if ignoredException(t, whitelist) => false
      case _                                            => true
    }

    val breakerConfig =
      if (config.hasPath(id)) config.getConfig(id).withFallback(defaultBreakerConfig)
      else defaultBreakerConfig

    if (breakerConfig.getBoolean("enabled")) {
      val maxFailures = breakerConfig.getInt("max-failures")
      val callTimeout = breakerConfig.getDuration("call-timeout", MILLISECONDS).millis
      val resetTimeout = breakerConfig.getDuration("reset-timeout", MILLISECONDS).millis

      val exceptionAllowlist: Set[String] = breakerConfig.getStringList("exception-allowlist").asScala.toSet

      val breaker = new CircuitBreaker(system.scheduler, maxFailures, callTimeout, resetTimeout)(system.dispatcher)
      val telemetry = CircuitBreakerTelemetryProvider.start(id, system)
      val failureFn = if (exceptionAllowlist.isEmpty) allExceptionAsFailure else failureDefinition(exceptionAllowlist)

      breaker.onClose(telemetry.onClose())
      breaker.onOpen(telemetry.onOpen())
      breaker.onHalfOpen(telemetry.onHalfOpen())

      Some(CircuitBreakerHolder(breaker, telemetry, failureFn))
    } else None
  }

  private def breaker(id: String): Option[CircuitBreakerHolder] =
    breakers.computeIfAbsent(id, createCircuitBreaker)

  def withCircuitBreaker[T](id: String)(body: => Future[T]): Future[T] = {
    breaker(id) match {
      case Some(CircuitBreakerHolder(b, telemetry, failureFn)) =>
        val startTime = System.nanoTime()

        def elapsed: Long = System.nanoTime() - startTime

        val result: Future[T] = b.withCircuitBreaker(body, failureFn)
        result.onComplete {
          case Success(_)                                        => telemetry.onCallSuccess(elapsed)
          case failure @ Failure(_) if !failureFn.apply(failure) => telemetry.onCallSuccess(elapsed)
          case Failure(_: CircuitBreakerOpenException)           => telemetry.onCallBreakerOpenFailure()
          case Failure(_: TimeoutException)                      => telemetry.onCallTimeoutFailure(elapsed)
          case Failure(_)                                        => telemetry.onCallFailure(elapsed)
        }(system.dispatcher)
        result
      case None => body
    }
  }

}

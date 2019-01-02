/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.annotation.DoNotInherit
import akka.stream.impl.streamref.StreamRefSettingsImpl
import com.typesafe.config.Config

import scala.concurrent.duration._

object StreamRefSettings {

  /** Java API */
  def create(system: ActorSystem): StreamRefSettings = apply(system)
  /** Scala API */
  def apply(system: ActorSystem): StreamRefSettings = {
    apply(system.settings.config.getConfig("akka.stream.materializer.stream-ref"))
  }

  /** Java API */
  def create(c: Config): StreamRefSettings = apply(c)
  /** Scala API */
  def apply(c: Config): StreamRefSettings = {
    StreamRefSettingsImpl(
      bufferCapacity = c.getInt("buffer-capacity"),
      demandRedeliveryInterval = c.getDuration("demand-redelivery-interval", TimeUnit.MILLISECONDS).millis,
      subscriptionTimeout = c.getDuration("subscription-timeout", TimeUnit.MILLISECONDS).millis,
      finalTerminationSignalDeadline = c.getDuration("final-termination-signal-deadline", TimeUnit.MILLISECONDS).millis
    )
  }
}

/**
 * Settings specific to [[SourceRef]] and [[SinkRef]].
 * More detailed documentation about each of the settings is available in `reference.conf`.
 */
@DoNotInherit
trait StreamRefSettings {
  def bufferCapacity: Int
  def demandRedeliveryInterval: FiniteDuration
  def subscriptionTimeout: FiniteDuration
  def finalTerminationSignalDeadline: FiniteDuration

  // --- with... methods ---

  def withBufferCapacity(value: Int): StreamRefSettings
  def withDemandRedeliveryInterval(value: FiniteDuration): StreamRefSettings
  def withSubscriptionTimeout(value: FiniteDuration): StreamRefSettings
  def withTerminationReceivedBeforeCompletionLeeway(value: FiniteDuration): StreamRefSettings
}


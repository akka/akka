/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.remote.scaladsl

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import com.typesafe.config.Config

final class StreamRefSettings(config: Config) {
  private val c = config.getConfig("akka.stream.stream-refs")

  val initialDemand = c.getInt("initial-demand")

  val demandRedeliveryInterval = c.getDuration("demand-redelivery-interval", TimeUnit.MILLISECONDS).millis

  val idleTimeout = c.getDuration("idle-timeout", TimeUnit.MILLISECONDS).millis
}

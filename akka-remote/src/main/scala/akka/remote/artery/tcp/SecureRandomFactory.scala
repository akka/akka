/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery.tcp

import java.security.SecureRandom

import akka.annotation.InternalApi
import akka.event.LogMarker
import akka.event.MarkerLoggingAdapter
import com.typesafe.config.Config

/**
 * INTERNAL API
 */
@InternalApi private[akka] object SecureRandomFactory {
  def createSecureRandom(config:Config, log: MarkerLoggingAdapter): SecureRandom = {
    createSecureRandom( config.getString("random-number-generator"), log)
  }
  def createSecureRandom(randomNumberGenerator: String, log: MarkerLoggingAdapter): SecureRandom = {
    val rng = randomNumberGenerator match {
      case s @ ("SHA1PRNG" | "NativePRNG") =>
        log.debug("SSL random number generator set to: {}", s)
        // SHA1PRNG needs /dev/urandom to be the source on Linux to prevent problems with /dev/random blocking
        // However, this also makes the seed source insecure as the seed is reused to avoid blocking (not a problem on FreeBSD).
        SecureRandom.getInstance(s)

      case "" | "SecureRandom" =>
        log.debug("SSL random number generator set to [SecureRandom]")
        new SecureRandom

      case unknown =>
        log.warning(
          LogMarker.Security,
          "Unknown SSL random number generator [{}] falling back to SecureRandom",
          unknown)
        new SecureRandom
    }
    rng.nextInt() // prevent stall on first access
    rng
  }
}

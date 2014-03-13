/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import language.implicitConversions

import java.util.concurrent.TimeUnit
import java.lang.{ Double ⇒ JDouble }
import scala.concurrent.duration.{ Duration, FiniteDuration }

@SerialVersionUID(1L)
case class Timeout(duration: FiniteDuration) {

  /**
   * Construct a Timeout from the given number of milliseconds.
   */
  @deprecated("please be explicit about the time unit and use the two-argument version", "2.3")
  def this(timeout: Long) = this(Duration(timeout, TimeUnit.MILLISECONDS))

  /**
   * Construct a Timeout from the given time unit and factor.
   */
  def this(length: Long, unit: TimeUnit) = this(Duration(length, unit))
}

/**
 * A Timeout is a wrapper on top of Duration to be more specific about what the duration means.
 */
object Timeout {

  /**
   * A timeout with zero duration, will cause most requests to always timeout.
   */
  val zero: Timeout = new Timeout(Duration.Zero)

  /**
   * Construct a Timeout from the given number of milliseconds.
   */
  @deprecated("please be explicit about the time unit and use the two-argument version", "2.3")
  def apply(timeout: Long): Timeout = new Timeout(timeout)

  /**
   * Construct a Timeout from the given time unit and factor.
   */
  def apply(length: Long, unit: TimeUnit): Timeout = new Timeout(length, unit)

  implicit def durationToTimeout(duration: FiniteDuration): Timeout = new Timeout(duration)
  implicit def intToTimeout(timeout: Int): Timeout = new Timeout(timeout)
  implicit def longToTimeout(timeout: Long): Timeout = new Timeout(timeout)
}

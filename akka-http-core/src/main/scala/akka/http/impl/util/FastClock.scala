/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import akka.actor.{ ActorSystem, Scheduler }

import scala.concurrent.duration._

/**
 * INTERNAL API
 * Clock which can be triggered from the outside to update it's time (otherwise returns the last observed time,
 * adjusted by a
 */
abstract class FastClock {
  def updateHighSpeed(): Long

  def highSpeedPart: Long
}

// nano trusting nano time...
final class NanosFastClock(nanosTrustLimit: Long) extends FastClock {
  private[this] var wallClock: Long = 0
  private[this] var highSpeedClock: Long = 0
  private[this] var highSpeedClockOffset: Long = 0

  updateWallClock()

  /** Returns current time in milliseconds, resets the highSpeed counter */
  def updateWallClock(): Long = {
    wallClock = System.currentTimeMillis()
    highSpeedClockOffset = System.nanoTime()
    highSpeedClock = 0
    wallClock
  }

  /** Updates internal highSpeed part and returns number of nanoseconds since last `update...()` call*/
  override def updateHighSpeed(): Long = {
    highSpeedClock = System.nanoTime() - highSpeedClockOffset
    highSpeedClock
  }

  override def highSpeedPart: Long = highSpeedClock
}

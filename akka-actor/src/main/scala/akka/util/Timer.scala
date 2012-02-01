package akka.util

import scala.util.Duration

/**
 * Simple timer class.
 * Usage:
 * <pre>
 *   import scala.util.duration._
 *   import akka.util.Timer
 *
 *   val timer = Timer(30.seconds)
 *   while (timer.isTicking) { ... }
 * </pre>
 */
case class Timer(duration: Duration, throwExceptionOnTimeout: Boolean = false) {
  val startTimeInMillis = System.currentTimeMillis
  val timeoutInMillis = duration.toMillis

  /**
   * Returns true while the timer is ticking. After that it either throws and exception or
   * returns false. Depending on if the 'throwExceptionOnTimeout' argument is true or false.
   */
  def isTicking: Boolean = {
    if (!(timeoutInMillis > (System.currentTimeMillis - startTimeInMillis))) {
      if (throwExceptionOnTimeout) throw new TimerException("Time out after " + duration)
      else false
    } else true
  }
}

class TimerException(message: String) extends RuntimeException(message)
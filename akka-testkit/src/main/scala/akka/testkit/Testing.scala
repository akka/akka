/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.testkit

import akka.util.Duration
import Duration.timeFactor

/**
 * Multiplying numbers used in test timeouts by a factor, set by system property.
 * Useful for Jenkins builds (where the machine may need more time).
 */
object Testing {
  def testTime(t: Int): Int = (timeFactor * t).toInt
  def testTime(t: Long): Long = (timeFactor * t).toLong
  def testTime(t: Float): Float = (timeFactor * t).toFloat
  def testTime(t: Double): Double = timeFactor * t

  def sleepFor(duration: Duration) = Thread.sleep(testTime(duration.toMillis))
}

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.testing

import akka.util.Duration

/**
 * Multiplying numbers used in test timeouts by a factor, set by system property.
 * Useful for Jenkins builds (where the machine may need more time).
 */
object Testing {
  val timeFactor: Double = {
    val factor = System.getProperty("akka.test.timefactor", "1.0")
    try {
      factor.toDouble
    } catch {
      case e: java.lang.NumberFormatException => 1.0
    }
  }

  def testTime(t: Int): Int = (timeFactor * t).toInt
  def testTime(t: Long): Long = (timeFactor * t).toLong
  def testTime(t: Float): Float = (timeFactor * t).toFloat
  def testTime(t: Double): Double = timeFactor * t

  def sleepFor(duration: Duration) = Thread.sleep(testTime(duration.toMillis))
}

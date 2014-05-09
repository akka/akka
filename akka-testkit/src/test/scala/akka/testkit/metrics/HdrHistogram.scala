/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics

import com.codahale.metrics.{ Snapshot, Sampling, Metric }
import org.{ HdrHistogram ⇒ hdr }
import java.util.{ Arrays, Collections }
import java.lang.Math._
import java.io.{ OutputStream, OutputStreamWriter, PrintWriter }

/**
 * Adapts Gil Tene's HdrHistogram to Metric's Metric interface.
 *
 * @param highestTrackableValue The highest value to be tracked by the histogram. Must be a positive
 *                              integer that is >= 2.
 * @param numberOfSignificantValueDigits The number of significant decimal digits to which the histogram will
 *                                       maintain value resolution and separation. Must be a non-negative
 *                                       integer between 0 and 5.
 */
private[akka] class HdrHistogram(
  highestTrackableValue: Long,
  numberOfSignificantValueDigits: Int,
  val unit: String = "")
  extends Metric {

  private val hist = new hdr.Histogram(highestTrackableValue, numberOfSignificantValueDigits)

  def update(value: Long) {
    hist.recordValue(value)
  }

  def updateWithExpectedInterval(value: Long, expectedIntervalBetweenValueSamples: Long) {
    hist.recordValueWithExpectedInterval(value, expectedIntervalBetweenValueSamples)
  }

  def updateWithCount(value: Long, count: Long) {
    hist.recordValueWithCount(value, count)
  }

  def getData = hist.copy().getHistogramData

}

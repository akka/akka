/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.metrics.reporter

import java.text.DateFormat
import java.util
import java.util.concurrent.TimeUnit
import com.codahale.metrics._
import java.util.{ Locale, Date }
import akka.testkit.metrics._
import scala.concurrent.duration._

/**
 * Used to report `com.codahale.metrics.Metric` types that the original `com.codahale.metrics.graphite.GraphiteReporter` is unaware of (cannot re-use directly because of private constructor).
 */
class AkkaGraphiteReporter(
  registry: AkkaMetricRegistry,
  prefix: String,
  graphite: GraphiteClient,
  verbose: Boolean = false)
  extends ScheduledReporter(registry.asInstanceOf[MetricRegistry], "akka-graphite-reporter", MetricFilter.ALL, TimeUnit.SECONDS, TimeUnit.NANOSECONDS) {

  // todo get rid of ScheduledReporter (would mean removing codahale metrics)?

  private final val ConsoleWidth = 80

  val locale = Locale.getDefault
  val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
  val clock = Clock.defaultClock()

  override def report(gauges: util.SortedMap[String, Gauge[_]], counters: util.SortedMap[String, Counter], histograms: util.SortedMap[String, Histogram], meters: util.SortedMap[String, Meter], timers: util.SortedMap[String, Timer]) {
    val dateTime = dateFormat.format(new Date(clock.getTime))

    // akka-custom metrics
    val knownOpsInTimespanCounters = registry.getKnownOpsInTimespanCounters
    val hdrHistograms = registry.getHdrHistograms
    val averagingGauges = registry.getAveragingGauges

    val metricsCount = List(gauges, counters, histograms, meters, timers).map(_.size).sum + List(knownOpsInTimespanCounters, hdrHistograms).map(_.size).sum
    sendWithBanner("== AkkaGraphiteReporter @ " + dateTime + " == (" + metricsCount + " metrics)", '=')

    try {
      // graphite takes timestamps in seconds
      val now = System.currentTimeMillis.millis.toSeconds

      // default Metrics types
      import collection.JavaConverters._
      sendMetrics(now, gauges.asScala, sendGauge)
      sendMetrics(now, counters.asScala, sendCounter)
      sendMetrics(now, histograms.asScala, sendHistogram)
      sendMetrics(now, meters.asScala, sendMetered)
      sendMetrics(now, timers.asScala, sendTimer)

      sendMetrics(now, knownOpsInTimespanCounters, sendKnownOpsInTimespanCounter)
      sendMetrics(now, hdrHistograms, sendHdrHistogram)
      sendMetrics(now, averagingGauges, sendAveragingGauge)

    } catch {
      case ex: Exception ⇒ throw new RuntimeException("Unable to send metrics to Graphite!", ex)
    }
  }

  def sendMetrics[T <: Metric](now: Long, metrics: Iterable[(String, T)], send: (Long, String, T) ⇒ Unit) {
    for ((key, metric) ← metrics) {
      if (verbose)
        println("  " + key)
      send(now, key, metric)
    }
  }

  private def sendHistogram(now: Long, key: String, histogram: Histogram) {
    val snapshot = histogram.getSnapshot
    send(key + ".count", histogram.getCount, now)
    send(key + ".max", snapshot.getMax, now)
    send(key + ".mean", snapshot.getMean, now)
    send(key + ".min", snapshot.getMin, now)
    send(key + ".stddev", snapshot.getStdDev, now)
    send(key + ".p50", snapshot.getMedian, now)
    send(key + ".p75", snapshot.get75thPercentile, now)
    send(key + ".p95", snapshot.get95thPercentile, now)
    send(key + ".p98", snapshot.get98thPercentile, now)
    send(key + ".p99", snapshot.get99thPercentile, now)
    send(key + ".p999", snapshot.get999thPercentile, now)
  }

  private def sendTimer(now: Long, key: String, timer: Timer) {
    val snapshot = timer.getSnapshot
    send(key + ".max", convertDuration(snapshot.getMax), now)
    send(key + ".mean", convertDuration(snapshot.getMean), now)
    send(key + ".min", convertDuration(snapshot.getMin), now)
    send(key + ".stddev", convertDuration(snapshot.getStdDev), now)
    send(key + ".p50", convertDuration(snapshot.getMedian), now)
    send(key + ".p75", convertDuration(snapshot.get75thPercentile), now)
    send(key + ".p95", convertDuration(snapshot.get95thPercentile), now)
    send(key + ".p98", convertDuration(snapshot.get98thPercentile), now)
    send(key + ".p99", convertDuration(snapshot.get99thPercentile), now)
    send(key + ".p999", convertDuration(snapshot.get999thPercentile), now)
    sendMetered(now, key, timer)
  }

  private def sendMetered(now: Long, key: String, meter: Metered) {
    send(key + ".count", meter.getCount, now)
    send(key + ".m1_rate", convertRate(meter.getOneMinuteRate), now)
    send(key + ".m5_rate", convertRate(meter.getFiveMinuteRate), now)
    send(key + ".m15_rate", convertRate(meter.getFifteenMinuteRate), now)
    send(key + ".mean_rate", convertRate(meter.getMeanRate), now)
  }

  private def sendGauge(now: Long, key: String, gauge: Gauge[_]) {
    sendNumericOrIgnore(key + ".gauge", gauge.getValue, now)
  }

  private def sendCounter(now: Long, key: String, counter: Counter) {
    sendNumericOrIgnore(key + ".count", counter.getCount, now)
  }

  private def sendKnownOpsInTimespanCounter(now: Long, key: String, counter: KnownOpsInTimespanTimer) {
    send(key + ".ops", counter.getCount, now)
    send(key + ".time", counter.elapsedTime, now)
    send(key + ".opsPerSec", counter.opsPerSecond, now)
    send(key + ".avg", counter.avgDuration, now)
  }

  private def sendHdrHistogram(now: Long, key: String, hist: HdrHistogram) {
    val snapshot = hist.getData
    send(key + ".min", snapshot.getMinValue, now)
    send(key + ".max", snapshot.getMaxValue, now)
    send(key + ".mean", snapshot.getMean, now)
    send(key + ".stddev", snapshot.getStdDeviation, now)
    send(key + ".p75", snapshot.getValueAtPercentile(75.0), now)
    send(key + ".p95", snapshot.getValueAtPercentile(95.0), now)
    send(key + ".p98", snapshot.getValueAtPercentile(98.0), now)
    send(key + ".p99", snapshot.getValueAtPercentile(99.0), now)
    send(key + ".p999", snapshot.getValueAtPercentile(99.9), now)
  }

  private def sendAveragingGauge(now: Long, key: String, gauge: AveragingGauge) {
    sendNumericOrIgnore(key + ".avg-gauge", gauge.getValue, now)
  }

  override def stop(): Unit = try {
    super.stop()
    graphite.close()
  } catch {
    case ex: Exception ⇒ System.err.println("Was unable to close Graphite connection: " + ex.getMessage)
  }

  private def sendNumericOrIgnore(key: String, value: Any, now: Long) {
    // seriously nothing better than this? (without Any => String => Num)
    value match {
      case v: Int    ⇒ send(key, v, now)
      case v: Long   ⇒ send(key, v, now)
      case v: Byte   ⇒ send(key, v, now)
      case v: Short  ⇒ send(key, v, now)
      case v: Float  ⇒ send(key, v, now)
      case v: Double ⇒ send(key, v, now)
      case _         ⇒ // ignore non-numeric metric...
    }
  }

  private def send(key: String, value: Double, now: Long) {
    if (value >= 0)
      graphite.send(s"$prefix.$key", "%2.2f".format(value), now)
  }

  private def send(key: String, value: Long, now: Long) {
    if (value >= 0)
      graphite.send(s"$prefix.$key", value.toString, now)
  }

  private def sendWithBanner(s: String, c: Char) {
    print(s)
    print(' ')
    var i: Int = 0
    while (i < (ConsoleWidth - s.length - 1)) {
      print(c)
      i += 1
    }
    println()
  }

}


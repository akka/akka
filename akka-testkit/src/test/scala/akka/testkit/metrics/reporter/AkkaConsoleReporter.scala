/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics.reporter

import java.io.PrintStream
import java.util
import java.util.concurrent.TimeUnit
import akka.event.Logging
import com.codahale.metrics._
import akka.testkit.metrics._
import scala.reflect.ClassTag

/**
 * Used to report [[Metric]] types that the original [[ConsoleReporter]] is unaware of (cannot re-use directly because of private constructor).
 */
class AkkaConsoleReporter(
  registry: AkkaMetricRegistry,
  verbose: Boolean,
  print: String ⇒ Unit = s ⇒ System.out.print(s))
  extends ScheduledReporter(registry.asInstanceOf[MetricRegistry], "akka-console-reporter", MetricsKit.KnownOpsInTimespanCounterFilter, TimeUnit.SECONDS, TimeUnit.NANOSECONDS) {

  private final val ConsoleWidth = 80

  override def report(gauges: util.SortedMap[String, Gauge[_]], counters: util.SortedMap[String, Counter], histograms: util.SortedMap[String, Histogram], meters: util.SortedMap[String, Meter], timers: util.SortedMap[String, Timer]) {
    import collection.JavaConverters._

    // default Metrics types
    printMetrics(gauges.asScala, printGauge)
    printMetrics(counters.asScala, printCounter)
    printMetrics(histograms.asScala, printHistogram)
    printMetrics(meters.asScala, printMeter)
    printMetrics(timers.asScala, printTimer)

    // custom Akka types
    printMetrics(registry.getKnownOpsInTimespanCounters, printKnownOpsInTimespanCounter)
    printMetrics(registry.getHdrHistograms, printHdrHistogram)
    printMetrics(registry.getAveragingGauges, printAveragingGauge)
  }

  def printMetrics[T <: Metric](metrics: Iterable[(String, T)], printer: T ⇒ Unit)(implicit clazz: ClassTag[T]) {
    if (!metrics.isEmpty) {
      printWithBanner(s"  ${Logging.simpleName(metrics.head._2.getClass)}", '-')
      for ((key, metric) ← metrics) {
        print(s"""  $key""".stripMargin)
        printer(metric)
      }
    }
  }

  private def printMeter(meter: Meter) {
    print("             count = %d".format(meter.getCount))
    print("         mean rate = %2.2f events/%s".format(convertRate(meter.getMeanRate), getRateUnit))
    print("     1-minute rate = %2.2f events/%s".format(convertRate(meter.getOneMinuteRate), getRateUnit))
    print("     5-minute rate = %2.2f events/%s".format(convertRate(meter.getFiveMinuteRate), getRateUnit))
    print("    15-minute rate = %2.2f events/%s".format(convertRate(meter.getFifteenMinuteRate), getRateUnit))
  }

  private def printCounter(entry: Counter) {
    print("             count = %d".format(entry.getCount))
  }

  private def printGauge(entry: Gauge[_]) {
    print("             value = %s".format(entry.getValue))
  }

  private def printHistogram(histogram: Histogram) {
    val snapshot = histogram.getSnapshot
    print("             count = %d".format(histogram.getCount))
    print("               min = %d".format(snapshot.getMin))
    print("               max = %d".format(snapshot.getMax))
    print("              mean = %2.2f".format(snapshot.getMean))
    print("            stddev = %2.2f".format(snapshot.getStdDev))
    print("            median = %2.2f".format(snapshot.getMedian))
    print("              75%% <= %2.2f".format(snapshot.get75thPercentile))
    print("              95%% <= %2.2f".format(snapshot.get95thPercentile))
    print("              98%% <= %2.2f".format(snapshot.get98thPercentile))
    print("              99%% <= %2.2f".format(snapshot.get99thPercentile))
    print("            99.9%% <= %2.2f".format(snapshot.get999thPercentile))
  }

  private def printTimer(timer: Timer) {
    val snapshot = timer.getSnapshot
    print("             count = %d".format(timer.getCount))
    print("         mean rate = %2.2f calls/%s".format(convertRate(timer.getMeanRate), getRateUnit))
    print("     1-minute rate = %2.2f calls/%s".format(convertRate(timer.getOneMinuteRate), getRateUnit))
    print("     5-minute rate = %2.2f calls/%s".format(convertRate(timer.getFiveMinuteRate), getRateUnit))
    print("    15-minute rate = %2.2f calls/%s".format(convertRate(timer.getFifteenMinuteRate), getRateUnit))
    print("               min = %2.2f %s".format(convertDuration(snapshot.getMin), getDurationUnit))
    print("               max = %2.2f %s".format(convertDuration(snapshot.getMax), getDurationUnit))
    print("              mean = %2.2f %s".format(convertDuration(snapshot.getMean), getDurationUnit))
    print("            stddev = %2.2f %s".format(convertDuration(snapshot.getStdDev), getDurationUnit))
    print("            median = %2.2f %s".format(convertDuration(snapshot.getMedian), getDurationUnit))
    print("              75%% <= %2.2f %s".format(convertDuration(snapshot.get75thPercentile), getDurationUnit))
    print("              95%% <= %2.2f %s".format(convertDuration(snapshot.get95thPercentile), getDurationUnit))
    print("              98%% <= %2.2f %s".format(convertDuration(snapshot.get98thPercentile), getDurationUnit))
    print("              99%% <= %2.2f %s".format(convertDuration(snapshot.get99thPercentile), getDurationUnit))
    print("            99.9%% <= %2.2f %s".format(convertDuration(snapshot.get999thPercentile), getDurationUnit))
  }

  private def printKnownOpsInTimespanCounter(counter: KnownOpsInTimespanTimer) {
    import concurrent.duration._
    import PrettyDuration._
    print("               ops = %d".format(counter.getCount))
    print("              time = %s".format(counter.elapsedTime.nanos.pretty))
    print("             ops/s = %2.2f".format(counter.opsPerSecond))
    print("               avg = %s".format(counter.avgDuration.nanos.pretty))
  }

  private def printHdrHistogram(hist: HdrHistogram) {
    val data = hist.getData
    val unit = hist.unit
    print("               min = %d %s".format(data.getMinValue, unit))
    print("               max = %d %s".format(data.getMaxValue, unit))
    print("              mean = %2.2f %s".format(data.getMean, unit))
    print("            stddev = %2.2f".format(data.getStdDeviation))
    print("              75%% <= %d %s".format(data.getValueAtPercentile(75.0), unit))
    print("              95%% <= %d %s".format(data.getValueAtPercentile(95.0), unit))
    print("              98%% <= %d %s".format(data.getValueAtPercentile(98.0), unit))
    print("              99%% <= %d %s".format(data.getValueAtPercentile(99.0), unit))
    print("            99.9%% <= %d %s".format(data.getValueAtPercentile(99.9), unit))
  }

  private def printAveragingGauge(gauge: AveragingGauge) {
    print("                avg = %2.2f".format(gauge.getValue))
  }

  private def printWithBanner(s: String, c: Char) {
    val sb = new StringBuilder(s + " ")
    var i: Int = 0
    while (i < (ConsoleWidth - s.length - 1)) {
      sb.append(c.toString)
      i += 1
    }
    print(sb.toString())
  }

}


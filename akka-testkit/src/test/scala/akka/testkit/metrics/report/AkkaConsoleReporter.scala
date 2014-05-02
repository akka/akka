/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics.report

import java.io.PrintStream
import java.text.DateFormat
import java.util
import java.util.concurrent.TimeUnit
import com.codahale.metrics._
import java.util.{ Locale, Date }
import akka.testkit.metrics.{ KnownOpsInTimespanCounter, AkkaMetricRegistry, MetricsKit }

class AkkaConsoleReporter(
  registry: AkkaMetricRegistry,
  durationUnit: TimeUnit,
  rateUnit: TimeUnit,
  output: PrintStream = System.out)
  extends ScheduledReporter(registry.asInstanceOf[MetricRegistry], "akka-console-reporter", MetricsKit.KnownOpsInTimespanCounterFilter, rateUnit, durationUnit) {

  private final val CONSOLE_WIDTH: Int = 80

  val locale = Locale.getDefault
  val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, locale)
  val clock = Clock.defaultClock()

  override def report(gauges: util.SortedMap[String, Gauge[_]], counters: util.SortedMap[String, Counter], histograms: util.SortedMap[String, Histogram], meters: util.SortedMap[String, Meter], timers: util.SortedMap[String, Timer]) {
    val dateTime: String = dateFormat.format(new Date(clock.getTime))
    printWithBanner(dateTime, '=')

    //    printGauges(gauges)
    //    printCounters(counters)
    //    printHistograms(histograms)
    //    printMeters(meters)
    //    printTimers(timers)
    printKnownOpsInTimespanCounters(registry.getKnownOpsInTimespanCounters)
    output.println()
    output.flush()
  }

  def printKnownOpsInTimespanCounters(counters: Iterable[KnownOpsInTimespanCounter]) {
    if (!counters.isEmpty) {
      printWithBanner("-- " + getClass.getSimpleName, '-')
      for (entry ← counters) {
        //        output.println(entry.getKey)
        printKnownOpsInTimespanCounter(entry)
      }
      output.println()
    }
  }

  private def printKnownOpsInTimespanCounter(counter: KnownOpsInTimespanCounter) {
    import concurrent.duration._
    import PrettyDuration._
    output.print("               ops = %d%n".format(counter.getCount))
    output.print("              time = %s%n".format(counter.elapsedTime.nanos.pretty))
    output.print("             ops/s = %2.2f%n".format(counter.opsPerSecond))
    output.print("               avg = %s%n".format(counter.avgDuration.nanos.pretty))
  }

  private def printWithBanner(s: String, c: Char) {
    output.print(s)
    output.print(' ')
    var i: Int = 0
    while (i < (CONSOLE_WIDTH - s.length - 1)) {
      output.print(c)
      i += 1
    }
    output.println()
  }

}


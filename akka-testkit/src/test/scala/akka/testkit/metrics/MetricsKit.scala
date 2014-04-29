/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit.metrics

import com.codahale.metrics._
import com.codahale.metrics.graphite.{ GraphiteReporter, Graphite }
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import com.typesafe.config.Config
import java.util

/**
 * Allows to easily measure performance / memory / file descriptor use in tests.
 *
 * WARNING: This trait should not be seen as utility for micro-benchmarking,
 * please refer to <a href="http://openjdk.java.net/projects/code-tools/jmh/">JMH</a> if that's what you're writing.
 * This trait instead aims to give an high level overview as well as data for trend-analysis of long running tests.
 *
 * Reporting defaults to [[ConsoleReporter]].
 * In order to send metrics to Graphite run sbt with the following property: `-Dakka.metrics.reporting.0=graphite`.
 */
private[akka] trait MetricsKit {

  private var reporters: List[ScheduledReporter] = Nil

  /**
   * A configuration containing [[MetricsKitSettings]] under the key `akka.test.metrics` must be provided.
   * This can be the ActorSystems config.
   *
   * The reason this is not handled by an Extension is thatwe do not want to enforce having to start an ActorSystem,
   * since code measured using this Kit may not need one (e.g. measuring plain Queue implementations).
   */
  def metricsConfig: Config

  private val metrics = new MetricRegistry()

  initMetricReporters()

  def initMetricReporters() {
    val settings = new MetricsKitSettings(metricsConfig)

    def configureConsoleReporter() {
      if (settings.Reporters.contains("console")) {
        val consoleReporter = ConsoleReporter.forRegistry(metrics)
          .outputTo(System.out)
          .convertDurationsTo(TimeUnit.MICROSECONDS)
          .build()

        if (settings.ConsoleReporter.ScheduledReportInterval > 0.millis)
          consoleReporter.start(settings.ConsoleReporter.ScheduledReportInterval.toMillis, TimeUnit.MILLISECONDS)

        reporters ::= consoleReporter
      }
    }

    def configureGraphiteReporter() {
      if (settings.Reporters.contains("graphite")) {
        println(s"Will send metrics to Graphite @ ${settings.GraphiteReporter.Host}:${settings.GraphiteReporter.Port}")

        val graphiteReporter = GraphiteReporter.forRegistry(metrics)
          .convertDurationsTo(TimeUnit.MICROSECONDS)
          .prefixedWith(settings.GraphiteReporter.Prefix)
          .build(new Graphite(new InetSocketAddress(settings.GraphiteReporter.Host, settings.GraphiteReporter.Port)))

        if (settings.GraphiteReporter.ScheduledReportInterval > 0.millis) {
          graphiteReporter.start(settings.GraphiteReporter.ScheduledReportInterval.toMillis, TimeUnit.MILLISECONDS)
        }

        reporters ::= graphiteReporter
      }
    }

    configureConsoleReporter()
    configureGraphiteReporter()
  }

  /**
   * Schedule metric reports execution iterval. Should not be used multiple times
   */
  def scheduleMetricReports(every: FiniteDuration) {
    reporters foreach { _.start(every.toMillis, TimeUnit.MILLISECONDS) }
  }

  def timer(name: String) = metrics.timer(name)

  /**
   * Convinience method for measuring long running piece of code.
   * HINT: don't use this form in loops, prefer using timer explicitly in tight loops.
   */
  def measureTime(name: String)(run: ⇒ Any) = {
    val t = timer(name)
    run
    t.time()
  }

  /**
   * Delegates to `System.gc()`.
   */
  def gc() {
    System.gc()
  }

  def measureMemoryUse(name: String) = {
    metrics.registerAll(new jvm.MemoryUsageGaugeSet() with MetricsPrefix { val prefix = name })
  }

  def measureGc(name: String) = {
    metrics.registerAll(new jvm.GarbageCollectorMetricSet() with MetricsPrefix { val prefix = name })
  }

  def measureFileDescriptors(name: String) = {
    metrics.registerAll(new FileDescriptorMetricSet() with MetricsPrefix { val prefix = name })
  }

  /**
   * Causes immediate flush of metrics using all registered reporters.
   *
   * HINT: this operation can be costy, run outside of your tested code, or rely on scheduled reporting.
   */
  def reportAllMetrics() {
    reporters foreach { _.report() }
  }

  /**
   * Removes registered metrics from registry.
   * You should call this method then you're done measuring something - usually at the end of your test case,
   * otherwise the metrics from different tests would influence each others results (avg, min, max, ...).
   *
   * Please note that, if you have registered a `timer("thing")` previously, you will need to call `timer("thing")` again,
   * in order to register a new timer.
   */
  def removeMetrics(matching: MetricFilter = MetricFilter.ALL) {
    metrics.removeMatching(matching)
  }

  /**
   * MUST be called after all tests have finished (in ``
   */
  def shutdownMetrics() {
    reporters foreach { _.stop() }
  }

  private trait MetricsPrefix extends MetricSet {
    def prefix: String
    abstract override def getMetrics: util.Map[String, Metric] = {
      // does not have to be fast, is only called once during registering metrics
      import collection.JavaConverters._
      (super.getMetrics.asScala.map { case (k, v) ⇒ (prefix + "." + k, v) }).asJava
    }
  }
}

private[akka] class MetricsKitSettings(config: Config) {

  import akka.util.Helpers._

  val Reporters = config.getStringList("akka.test.metrics.reporters")

  object GraphiteReporter {
    val Prefix = config.getString("akka.test.metrics.reporter.graphite.prefix")
    lazy val Host = config.getString("akka.test.metrics.reporter.graphite.host").requiring(v ⇒ !v.trim.isEmpty, "akka.test.metrics.reporter.graphite.host was used but was empty!")
    val Port = config.getInt("akka.test.metrics.reporter.graphite.port")

    val ScheduledReportInterval = config.getMillisDuration("akka.test.metrics.reporter.graphite.scheduled-report-interval")
  }

  object ConsoleReporter {
    val ScheduledReportInterval = config.getMillisDuration("akka.test.metrics.reporter.console.scheduled-report-interval")
  }

}

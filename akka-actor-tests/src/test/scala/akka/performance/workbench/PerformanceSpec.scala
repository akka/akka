package akka.performance.workbench

import scala.collection.immutable.TreeMap
import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.scalatest.BeforeAndAfterEach
import akka.testkit.AkkaSpec
import scala.concurrent.duration.Duration
import com.typesafe.config.Config
import java.util.concurrent.TimeUnit
import akka.event.Logging

abstract class PerformanceSpec(cfg: Config = BenchmarkConfig.config) extends AkkaSpec(cfg) with BeforeAndAfterEach {

  def config = system.settings.config
  def isLongRunningBenchmark() = config.getBoolean("benchmark.longRunning")
  def minClients() = config.getInt("benchmark.minClients")
  def maxClients() = config.getInt("benchmark.maxClients")
  def repeatFactor() = config.getInt("benchmark.repeatFactor")
  def timeDilation() = config.getLong("benchmark.timeDilation")
  def maxRunDuration() = Duration(config.getMilliseconds("benchmark.maxRunDuration"), TimeUnit.MILLISECONDS)
  def clientDelay = Duration(config.getNanoseconds("benchmark.clientDelay"), TimeUnit.NANOSECONDS)

  val resultRepository = BenchResultRepository()
  lazy val report = new Report(system, resultRepository, compareResultWith)

  /**
   * To compare two tests with each other you can override this method, in
   * the test. For example Some("OneWayPerformanceTest")
   */
  def compareResultWith: Option[String] = None

  def acceptClients(numberOfClients: Int): Boolean = {
    (minClients <= numberOfClients && numberOfClients <= maxClients &&
      (maxClients <= 16 || numberOfClients % 4 == 0))
  }

  def logMeasurement(numberOfClients: Int, durationNs: Long, n: Long) {
    val name = Logging.simpleName(this)
    val durationS = durationNs.toDouble / 1000000000.0

    val stats = Stats(
      name,
      load = numberOfClients,
      timestamp = TestStart.startTime,
      durationNanos = durationNs,
      n = n,
      tps = (n.toDouble / durationS))

    logMeasurement(stats)
  }

  def logMeasurement(numberOfClients: Int, durationNs: Long, stat: DescriptiveStatistics) {
    val name = Logging.simpleName(this)
    val durationS = durationNs.toDouble / 1000000000.0

    val percentiles = TreeMap[Int, Long](
      5 -> (stat.getPercentile(5.0) / 1000).toLong,
      25 -> (stat.getPercentile(25.0) / 1000).toLong,
      50 -> (stat.getPercentile(50.0) / 1000).toLong,
      75 -> (stat.getPercentile(75.0) / 1000).toLong,
      95 -> (stat.getPercentile(95.0) / 1000).toLong)

    val n = stat.getN

    val stats = Stats(
      name,
      load = numberOfClients,
      timestamp = TestStart.startTime,
      durationNanos = durationNs,
      n = n,
      min = (stat.getMin / 1000).toLong,
      max = (stat.getMax / 1000).toLong,
      mean = (stat.getMean / 1000).toLong,
      tps = (n.toDouble / durationS),
      percentiles)

    logMeasurement(stats)
  }

  def logMeasurement(stats: Stats) {
    try {
      resultRepository.add(stats)
      report.html(resultRepository.get(stats.name))
    } catch {
      // don't fail test due to problems saving bench report
      case e: Exception ⇒ log.error(e, e.getMessage)
    }
  }

}

object PerformanceSpec {
  def shortDelay(micros: Int, n: Long) {
    if (micros > 0) {
      val sampling = 1000 / micros
      if (n % sampling == 0) {
        Thread.sleep(1)
      }
    }
  }
}

object TestStart {
  val startTime = System.currentTimeMillis
}


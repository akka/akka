package akka.performance.workbench

import scala.collection.immutable.TreeMap

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.scalatest.BeforeAndAfterEach

import akka.actor.simpleName
import akka.testkit.AkkaSpec
import akka.AkkaApplication

trait PerformanceSpec extends AkkaSpec with BeforeAndAfterEach {

  def app: AkkaApplication

  def isBenchmark() = System.getProperty("benchmark") == "true"

  def minClients() = System.getProperty("benchmark.minClients", "1").toInt;

  def maxClients() = System.getProperty("benchmark.maxClients", "40").toInt;

  def repeatFactor() = {
    val defaultRepeatFactor = if (isBenchmark) "150" else "2"
    System.getProperty("benchmark.repeatFactor", defaultRepeatFactor).toInt
  }

  def timeDilation() = {
    System.getProperty("benchmark.timeDilation", "1").toLong
  }

  val resultRepository = BenchResultRepository()
  lazy val report = new Report(app, resultRepository, compareResultWith)

  /**
   * To compare two tests with each other you can override this method, in
   * the test. For example Some("OneWayPerformanceTest")
   */
  def compareResultWith: Option[String] = None

  def acceptClients(numberOfClients: Int): Boolean = {
    (minClients <= numberOfClients && numberOfClients <= maxClients)
  }

  def logMeasurement(numberOfClients: Int, durationNs: Long, n: Long) {
    val name = simpleName(this)
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
    val name = simpleName(this)
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
      case e: Exception ⇒ app.eventHandler.error(e, this, e.getMessage)
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


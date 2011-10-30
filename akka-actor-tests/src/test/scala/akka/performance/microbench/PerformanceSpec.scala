package akka.performance.microbench

import scala.collection.immutable.TreeMap

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math.stat.descriptive.SynchronizedDescriptiveStatistics
import org.scalatest.BeforeAndAfterEach

import akka.actor.simpleName
import akka.performance.workbench.BenchResultRepository
import akka.performance.workbench.Report
import akka.performance.workbench.Stats
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

  def sampling = {
    System.getProperty("benchmark.sampling", "200").toInt
  }

  var stat: DescriptiveStatistics = _

  override def beforeEach() {
    stat = new SynchronizedDescriptiveStatistics
  }

  val resultRepository = BenchResultRepository()
  lazy val report = new Report(app, resultRepository, compareResultWith)

  /**
   * To compare two tests with each other you can override this method, in
   * the test. For example Some("OneWayPerformanceTest")
   */
  def compareResultWith: Option[String] = None

  def logMeasurement(scenario: String, numberOfClients: Int, durationNs: Long) {
    try {
      val name = simpleName(this)
      val durationS = durationNs.toDouble / 1000000000.0

      val percentiles = TreeMap[Int, Long](
        5 -> (stat.getPercentile(5.0) / 1000).toLong,
        25 -> (stat.getPercentile(25.0) / 1000).toLong,
        50 -> (stat.getPercentile(50.0) / 1000).toLong,
        75 -> (stat.getPercentile(75.0) / 1000).toLong,
        95 -> (stat.getPercentile(95.0) / 1000).toLong)

      val n = stat.getN * sampling

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

      resultRepository.add(stats)

      report.html(resultRepository.get(name))
    } catch {
      // don't fail test due to problems saving bench report
      case e: Exception ⇒ app.eventHandler.error(this, e.getMessage)
    }
  }

}

object TestStart {
  val startTime = System.currentTimeMillis
}


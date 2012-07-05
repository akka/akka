package akka.performance.trading.common

import java.util.Random

import scala.collection.immutable.TreeMap

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math.stat.descriptive.SynchronizedDescriptiveStatistics
import org.junit.After
import org.junit.Before
import org.scalatest.junit.JUnitSuite

import akka.performance.trading.domain.Ask
import akka.performance.trading.domain.Bid
import akka.performance.trading.domain.Order
import akka.performance.trading.domain.TotalTradeCounter
import akka.performance.workbench.BenchResultRepository
import akka.performance.workbench.Report
import akka.performance.workbench.Stats
import akka.actor.simpleName

trait PerformanceTest extends JUnitSuite {

  //	jvm parameters
  //	-server -Xms512m -Xmx1024m -XX:+UseConcMarkSweepGC

  var isWarm = false

  def isBenchmark() = System.getProperty("benchmark") == "true"

  def minClients() = System.getProperty("benchmark.minClients", "1").toInt;

  def maxClients() = System.getProperty("benchmark.maxClients", "40").toInt;

  def repeatFactor() = {
    val defaultRepeatFactor = if (isBenchmark) "150" else "2"
    System.getProperty("benchmark.repeatFactor", defaultRepeatFactor).toInt
  }

  def warmupRepeatFactor() = {
    val defaultRepeatFactor = if (isBenchmark) "200" else "1"
    System.getProperty("benchmark.warmupRepeatFactor", defaultRepeatFactor).toInt
  }

  def randomSeed() = {
    System.getProperty("benchmark.randomSeed", "0").toInt
  }

  def timeDilation() = {
    System.getProperty("benchmark.timeDilation", "1").toLong
  }

  var stat: DescriptiveStatistics = _

  val resultRepository = BenchResultRepository()
  lazy val report = new Report(resultRepository, compareResultWith)

  type TS <: TradingSystem

  var tradingSystem: TS = _
  val random: Random = new Random(randomSeed)

  def createTradingSystem(): TS

  def placeOrder(orderReceiver: TS#OR, order: Order): Rsp

  def runScenario(scenario: String, orders: List[Order], repeat: Int, numberOfClients: Int, delayMs: Int)

  @Before
  def setUp() {
    stat = new SynchronizedDescriptiveStatistics
    tradingSystem = createTradingSystem()
    tradingSystem.start()
    warmUp()
    TotalTradeCounter.reset()
    stat = new SynchronizedDescriptiveStatistics
  }

  @After
  def tearDown() {
    tradingSystem.shutdown()
    stat = null
  }

  def warmUp() {
    val bid = new Bid("A1", 100, 1000)
    val ask = new Ask("A1", 100, 1000)

    val orderReceiver = tradingSystem.orderReceivers.head
    val loopCount = if (isWarm) 1 else 10 * warmupRepeatFactor

    for (i ← 1 to loopCount) {
      placeOrder(orderReceiver, bid)
      placeOrder(orderReceiver, ask)
    }
    isWarm = true
  }

  /**
   * To compare two tests with each other you can override this method, in
   * the test. For example Some("OneWayPerformanceTest")
   */
  def compareResultWith: Option[String] = None

  def logMeasurement(scenario: String, numberOfClients: Int, durationNs: Long) {

    val name = simpleName(this)
    val durationS = durationNs.toDouble / 1000000000.0

    val percentiles = TreeMap[Int, Long](
      5 -> (stat.getPercentile(5.0) / 1000).toLong,
      25 -> (stat.getPercentile(25.0) / 1000).toLong,
      50 -> (stat.getPercentile(50.0) / 1000).toLong,
      75 -> (stat.getPercentile(75.0) / 1000).toLong,
      95 -> (stat.getPercentile(95.0) / 1000).toLong)

    val stats = Stats(
      name,
      load = numberOfClients,
      timestamp = TestStart.startTime,
      durationNanos = durationNs,
      n = stat.getN,
      min = (stat.getMin / 1000).toLong,
      max = (stat.getMax / 1000).toLong,
      mean = (stat.getMean / 1000).toLong,
      tps = (stat.getN.toDouble / durationS),
      percentiles)

    resultRepository.add(stats)

    report.html(resultRepository.get(name))
  }

  def delay(delayMs: Int) {
    val adjustedDelay =
      if (delayMs >= 5) {
        val dist = 0.2 * delayMs
        (delayMs + random.nextGaussian * dist).intValue
      } else {
        delayMs
      }

    if (adjustedDelay > 0) {
      Thread.sleep(adjustedDelay)
    }
  }

}

object TestStart {
  val startTime = System.currentTimeMillis
}


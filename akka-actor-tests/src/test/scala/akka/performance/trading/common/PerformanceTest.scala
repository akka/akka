package akka.performance.trading.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Random

import scala.collection.immutable.TreeMap

import org.apache.commons.math.stat.descriptive.DescriptiveStatistics
import org.apache.commons.math.stat.descriptive.SynchronizedDescriptiveStatistics
import org.junit.After
import org.junit.Before
import org.scalatest.junit.JUnitSuite

import akka.event.EventHandler
import akka.performance.trading.domain.Ask
import akka.performance.trading.domain.Bid
import akka.performance.trading.domain.Order
import akka.performance.trading.domain.TotalTradeCounter

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

  val legendTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")

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

    val name = getClass.getSimpleName
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

    EventHandler.info(this, formatResultsTable(resultRepository.get(name)))

    percentilesChart(stats)
    latencyAndThroughputChart(stats)
    comparePercentilesChart(stats)
    compareWithHistoricalPercentiliesChart(stats)

  }

  def percentilesChart(stats: Stats) {
    val chartTitle = stats.name + " Percentiles (microseconds)"
    val chartUrl = GoogleChartBuilder.percentilChartUrl(resultRepository.get(stats.name), chartTitle, _.load + " clients")
    EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
  }

  def comparePercentilesChart(stats: Stats) {
    for {
      compareName ← compareResultWith
      compareStats ← resultRepository.get(compareName, stats.load)
    } {
      val chartTitle = stats.name + " vs. " + compareName + ", " + stats.load + " clients" + ", Percentiles (microseconds)"
      val chartUrl = GoogleChartBuilder.percentilChartUrl(Seq(compareStats, stats), chartTitle, _.name)
      EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
    }
  }

  def compareWithHistoricalPercentiliesChart(stats: Stats) {
    val withHistorical = resultRepository.getWithHistorical(stats.name, stats.load)
    if (withHistorical.size > 1) {
      val chartTitle = stats.name + " vs. historical, " + stats.load + " clients" + ", Percentiles (microseconds)"
      val chartUrl = GoogleChartBuilder.percentilChartUrl(withHistorical, chartTitle,
        stats ⇒ legendTimeFormat.format(new Date(stats.timestamp)))
      EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
    }
  }

  def latencyAndThroughputChart(stats: Stats) {
    val chartTitle = stats.name + " Latency (microseconds) and Throughput (TPS)"
    val chartUrl = GoogleChartBuilder.latencyAndThroughputChartUrl(resultRepository.get(stats.name), chartTitle)
    EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
  }

  def formatResultsTable(statsSeq: Seq[Stats]): String = {

    val name = statsSeq.head.name

    val spaces = "                                                                                     "
    val headerScenarioCol = ("Scenario" + spaces).take(name.length)

    val headerLine = (headerScenarioCol :: "clients" :: "TPS" :: "mean" :: "5%  " :: "25% " :: "50% " :: "75% " :: "95% " :: "Durat." :: "N" :: Nil)
      .mkString("\t")
    val headerLine2 = (spaces.take(name.length) :: "       " :: "   " :: "(us)" :: "(us)" :: "(us)" :: "(us)" :: "(us)" :: "(us)" :: "(s)   " :: " " :: Nil)
      .mkString("\t")
    val line = List.fill(formatStats(statsSeq.head).replaceAll("\t", "      ").length)("-").mkString
    val formattedStats = "\n" +
      line.replace('-', '=') + "\n" +
      headerLine + "\n" +
      headerLine2 + "\n" +
      line + "\n" +
      statsSeq.map(formatStats(_)).mkString("\n") + "\n" +
      line + "\n"

    formattedStats

  }

  def formatStats(stats: Stats): String = {
    val durationS = stats.durationNanos.toDouble / 1000000000.0
    val duration = durationS.formatted("%.0f")

    val tpsStr = stats.tps.formatted("%.0f")
    val meanStr = stats.mean.formatted("%.0f")

    val summaryLine =
      stats.name ::
        stats.load.toString ::
        tpsStr ::
        meanStr ::
        stats.percentiles(5).toString ::
        stats.percentiles(25).toString ::
        stats.percentiles(50).toString ::
        stats.percentiles(75).toString ::
        stats.percentiles(95).toString ::
        duration ::
        stats.n.toString ::
        Nil

    summaryLine.mkString("\t")

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


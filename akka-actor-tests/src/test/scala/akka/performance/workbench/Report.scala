package akka.performance.workbench

import java.lang.management.ManagementFactory
import java.text.SimpleDateFormat
import java.util.Date

import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.JavaConversions.enumerationAsScalaIterator

import akka.event.EventHandler
import akka.config.Config
import akka.config.Config.config

class Report(
  resultRepository: BenchResultRepository,
  compareResultWith: Option[String] = None) {

  private def log = System.getProperty("benchmark.logResult", "true").toBoolean

  val dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
  val legendTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
  val fileTimestampFormat = new SimpleDateFormat("yyyyMMddHHmmss")

  def html(statistics: Seq[Stats]): Unit = {

    val current = statistics.last
    val sb = new StringBuilder

    val title = current.name + " " + dateTimeFormat.format(new Date(current.timestamp))
    sb.append(header(title))
    sb.append("<h1>%s</h1>\n".format(title))

    sb.append("<pre>\n")
    val resultTable = formatResultsTable(statistics)
    sb.append(resultTable)
    sb.append("\n</pre>\n")

    sb.append(img(percentilesAndMeanChart(current)))
    sb.append(img(latencyAndThroughputChart(current)))

    for (stats ← statistics) {
      compareWithHistoricalPercentiliesAndMeanChart(stats).foreach(url ⇒ sb.append(img(url)))
    }

    for (stats ← statistics) {
      comparePercentilesAndMeanChart(stats).foreach(url ⇒ sb.append(img(url)))
    }

    sb.append("<hr/>\n")
    sb.append("<pre>\n")
    sb.append(systemInformation)
    sb.append("\n</pre>\n")

    val timestamp = fileTimestampFormat.format(new Date(current.timestamp))
    val reportName = current.name + "--" + timestamp + ".html"
    resultRepository.saveHtmlReport(sb.toString, reportName)

    if (log) {
      EventHandler.info(this, resultTable + "Charts in html report: " + resultRepository.htmlReportUrl(reportName))
    }

  }

  def img(url: String): String = {
    """<img src="%s" border="0" width="%s" height="%s" />""".format(
      url, GoogleChartBuilder.ChartWidth, GoogleChartBuilder.ChartHeight) + "\n"
  }

  def percentilesAndMeanChart(stats: Stats): String = {
    val chartTitle = stats.name + " Percentiles and Mean (microseconds)"
    val chartUrl = GoogleChartBuilder.percentilesAndMeanChartUrl(resultRepository.get(stats.name), chartTitle, _.load + " clients")
    chartUrl
  }

  def comparePercentilesAndMeanChart(stats: Stats): Seq[String] = {
    for {
      compareName ← compareResultWith.toSeq
      compareStats ← resultRepository.get(compareName, stats.load)
    } yield {
      val chartTitle = stats.name + " vs. " + compareName + ", " + stats.load + " clients" + ", Percentiles and Mean (microseconds)"
      val chartUrl = GoogleChartBuilder.percentilesAndMeanChartUrl(Seq(compareStats, stats), chartTitle, _.name)
      chartUrl
    }
  }

  def compareWithHistoricalPercentiliesAndMeanChart(stats: Stats): Option[String] = {
    val withHistorical = resultRepository.getWithHistorical(stats.name, stats.load)
    if (withHistorical.size > 1) {
      val chartTitle = stats.name + " vs. historical, " + stats.load + " clients" + ", Percentiles and Mean (microseconds)"
      val chartUrl = GoogleChartBuilder.percentilesAndMeanChartUrl(withHistorical, chartTitle,
        stats ⇒ legendTimeFormat.format(new Date(stats.timestamp)))
      Some(chartUrl)
    } else {
      None
    }
  }

  def latencyAndThroughputChart(stats: Stats): String = {
    val chartTitle = stats.name + " Latency (microseconds) and Throughput (TPS)"
    val chartUrl = GoogleChartBuilder.latencyAndThroughputChartUrl(resultRepository.get(stats.name), chartTitle)
    chartUrl
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

  def systemInformation: String = {
    val runtime = ManagementFactory.getRuntimeMXBean
    val os = ManagementFactory.getOperatingSystemMXBean
    val threads = ManagementFactory.getThreadMXBean
    val mem = ManagementFactory.getMemoryMXBean
    val heap = mem.getHeapMemoryUsage

    val sb = new StringBuilder

    sb.append("Benchmark properties:")
    import scala.collection.JavaConversions._
    val propNames: Seq[String] = System.getProperties.propertyNames.toSeq.map(_.toString)
    for (name ← propNames if name.startsWith("benchmark")) {
      sb.append("\n  ").append(name).append("=").append(System.getProperty(name))
    }
    sb.append("\n")

    sb.append("Operating system: ").append(os.getName).append(", ").append(os.getArch).append(", ").append(os.getVersion)
    sb.append("\n")
    sb.append("JVM: ").append(runtime.getVmName).append(" ").append(runtime.getVmVendor).
      append(" ").append(runtime.getVmVersion)
    sb.append("\n")
    sb.append("Processors: ").append(os.getAvailableProcessors)
    sb.append("\n")
    sb.append("Load average: ").append(os.getSystemLoadAverage)
    sb.append("\n")
    sb.append("Thread count: ").append(threads.getThreadCount).append(" (").append(threads.getPeakThreadCount).append(")")
    sb.append("\n")
    sb.append("Heap: ").append(formatDouble(heap.getUsed.toDouble / 1024 / 1024)).
      append(" (").append(formatDouble(heap.getInit.toDouble / 1024 / 1024)).
      append(" - ").
      append(formatDouble(heap.getMax.toDouble / 1024 / 1024)).
      append(")").append(" MB")
    sb.append("\n")

    val args = runtime.getInputArguments.filterNot(_.contains("classpath")).mkString("\n  ")
    sb.append("Args:\n  ").append(args)
    sb.append("\n")

    sb.append("Akka version: ").append(Config.CONFIG_VERSION)
    sb.append("\n")
    sb.append("Akka config:")
    for (key ← config.keys) {
      sb.append("\n  ").append(key).append("=").append(config(key))
    }

    sb.toString
  }

  def formatDouble(value: Double): String = {
    new java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_EVEN).toString
  }

  def header(title: String) =
    """|<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN" "http://www.w3.org/TR/html4/loose.dtd">
        |<html>
        |<head>
        |
        |<title>%s</title>
        |</head>
        |<body>
        |""".stripMargin.format(title)

  def footer =
    """|</body>"
        |</html>""".stripMargin

}
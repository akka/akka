package akka.performance.trading.common
import java.io.File
import java.text.SimpleDateFormat
import java.io.PrintWriter
import java.io.FileWriter
import akka.event.EventHandler
import java.util.Date

class Report(
  resultRepository: BenchResultRepository,
  compareResultWith: Option[String] = None) {

  private val dir = System.getProperty("benchmark.resultDir", "target/benchmark")

  private def dirExists: Boolean = new File(dir).exists
  private def log = System.getProperty("benchmark.logResult", "false").toBoolean

  val dateTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
  val legendTimeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm")
  val fileTimestampFormat = new SimpleDateFormat("yyyyMMddHHmmss")

  def html(statistics: Seq[Stats]): Unit = if (dirExists) {

    val current = statistics.last
    val sb = new StringBuilder

    val title = current.name + " " + dateTimeFormat.format(new Date(current.timestamp))
    sb.append(header(title))
    sb.append("<h1>%s</h1>\n".format(title))

    sb.append("<pre>\n")
    sb.append(formatResultsTable(statistics))
    sb.append("\n</pre>\n")

    sb.append(img(percentilesChart(current)))
    sb.append(img(latencyAndThroughputChart(current)))

    for (stats ← statistics) {
      compareWithHistoricalPercentiliesChart(stats).foreach(url ⇒ sb.append(img(url)))
    }

    for (stats ← statistics) {
      comparePercentilesChart(stats).foreach(url ⇒ sb.append(img(url)))
    }

    if (dirExists) {
      val timestamp = fileTimestampFormat.format(new Date(current.timestamp))
      val name = current.name + "--" + timestamp + ".html"
      write(sb.toString, name)
    }

  }

  private def img(url: String): String = {
    """<img src="%s" border="0" width="%s" height="%s" />""".format(
      url, GoogleChartBuilder.ChartWidth, GoogleChartBuilder.ChartHeight) + "\n"
  }

  def percentilesChart(stats: Stats): String = {
    val chartTitle = stats.name + " Percentiles (microseconds)"
    val chartUrl = GoogleChartBuilder.percentilChartUrl(resultRepository.get(stats.name), chartTitle, _.load + " clients")
    if (log) EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
    chartUrl
  }

  def comparePercentilesChart(stats: Stats): Seq[String] = {
    for {
      compareName ← compareResultWith.toSeq
      compareStats ← resultRepository.get(compareName, stats.load)
    } yield {
      val chartTitle = stats.name + " vs. " + compareName + ", " + stats.load + " clients" + ", Percentiles (microseconds)"
      val chartUrl = GoogleChartBuilder.percentilChartUrl(Seq(compareStats, stats), chartTitle, _.name)
      if (log) EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
      chartUrl
    }
  }

  def compareWithHistoricalPercentiliesChart(stats: Stats): Option[String] = {
    val withHistorical = resultRepository.getWithHistorical(stats.name, stats.load)
    if (withHistorical.size > 1) {
      val chartTitle = stats.name + " vs. historical, " + stats.load + " clients" + ", Percentiles (microseconds)"
      val chartUrl = GoogleChartBuilder.percentilChartUrl(withHistorical, chartTitle,
        stats ⇒ legendTimeFormat.format(new Date(stats.timestamp)))
      if (log) EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
      Some(chartUrl)
    } else {
      None
    }
  }

  def latencyAndThroughputChart(stats: Stats): String = {
    val chartTitle = stats.name + " Latency (microseconds) and Throughput (TPS)"
    val chartUrl = GoogleChartBuilder.latencyAndThroughputChartUrl(resultRepository.get(stats.name), chartTitle)
    if (log) EventHandler.info(this, chartTitle + " Chart:\n" + chartUrl)
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

    if (log) EventHandler.info(this, formattedStats)

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

  def write(content: String, fileName: String) {
    val f = new File(dir, fileName)
    var writer: PrintWriter = null
    try {
      writer = new PrintWriter(new FileWriter(f))
      writer.print(content)
      writer.flush()
    } catch {
      case e: Exception ⇒
        EventHandler.error(this, "Failed to save report to [%s], due to [%s]".
          format(f.getAbsolutePath, e.getMessage))
    } finally {
      if (writer ne null) try { writer.close() } catch { case ignore: Exception ⇒ }
    }
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
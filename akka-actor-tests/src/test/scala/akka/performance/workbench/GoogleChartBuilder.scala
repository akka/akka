package akka.performance.workbench

import java.io.UnsupportedEncodingException
import java.net.URLEncoder

import scala.collection.immutable

/**
 * Generates URLs to Google Chart API http://code.google.com/apis/chart/
 */
object GoogleChartBuilder {
  val BaseUrl = "http://chart.apis.google.com/chart?"
  val ChartWidth = 750
  val ChartHeight = 400

  /**
   * Builds a bar chart for tps in the statistics.
   */
  def tpsChartUrl(statsByTimestamp: immutable.TreeMap[Long, Seq[Stats]], title: String, legend: Stats ⇒ String): String = {
    if (statsByTimestamp.isEmpty) ""
    else {
      val loads = statsByTimestamp.values.head.map(_.load)
      val allStats = statsByTimestamp.values.flatten

      val sb = new StringBuilder
      sb.append(BaseUrl)
      // bar chart
      sb.append("cht=bvg")
      sb.append("&")
      // size
      sb.append("chs=").append(ChartWidth).append("x").append(ChartHeight)
      sb.append("&")
      // title
      sb.append("chtt=").append(urlEncode(title))
      sb.append("&")
      // axis locations
      sb.append("chxt=y,x")
      sb.append("&")
      // labels
      sb.append("chxl=1:|")
      sb.append(loads.mkString("|"))
      sb.append("&")

      // label color and font
      //sb.append("chxs=2,D65D82,11.5,0,lt,D65D82")
      //sb.append("&")

      // legend
      val legendStats = statsByTimestamp.values.toVector.map(_.head)
      appendLegend(legendStats, sb, legend)
      sb.append("&")
      // bar spacing
      sb.append("chbh=a,4,20")
      sb.append("&")
      // bar colors
      barColors(statsByTimestamp.size, sb)
      sb.append("&")

      // data series
      val loadStr = loads.mkString(",")
      sb.append("chd=t:")
      val maxValue = allStats.map(_.tps).max
      val tpsSeries: Iterable[String] = for (statsSeq ← statsByTimestamp.values) yield statsSeq.map(_.tps).mkString(",")
      sb.append(tpsSeries.mkString("|"))

      // y range
      sb.append("&")
      sb.append("chxr=0,0,").append(maxValue)
      sb.append("&")
      sb.append("chds=0,").append(maxValue)
      sb.append("&")

      // grid lines
      appendGridSpacing(maxValue.toLong, sb)

      sb.toString
    }
  }

  /**
   * Builds a bar chart for all percentiles and the mean in the statistics.
   */
  def percentilesAndMeanChartUrl(statistics: immutable.Seq[Stats], title: String, legend: Stats ⇒ String): String = {
    if (statistics.isEmpty) ""
    else {
      val current = statistics.last

      val sb = new StringBuilder
      sb.append(BaseUrl)
      // bar chart
      sb.append("cht=bvg")
      sb.append("&")
      // size
      sb.append("chs=").append(ChartWidth).append("x").append(ChartHeight)
      sb.append("&")
      // title
      sb.append("chtt=").append(urlEncode(title))
      sb.append("&")
      // axis locations
      sb.append("chxt=y,x,y")
      sb.append("&")
      // labels
      percentileLabels(current.percentiles, sb)
      sb.append("|mean")
      sb.append("|2:|min|mean|median")
      sb.append("&")
      // label positions
      sb.append("chxp=2,").append(current.min).append(",").append(current.mean).append(",")
        .append(current.median)
      sb.append("&")
      // label color and font
      sb.append("chxs=2,D65D82,11.5,0,lt,D65D82")
      sb.append("&")
      // lines for min, mean, median
      sb.append("chxtc=2,-1000")
      sb.append("&")
      // legend
      appendLegend(statistics, sb, legend)
      sb.append("&")
      // bar spacing
      sb.append("chbh=a,4,20")
      sb.append("&")
      // bar colors
      barColors(statistics.size, sb)
      sb.append("&")

      // data series
      val maxValue = statistics.map(_.percentiles.last._2).max
      sb.append("chd=t:")
      dataSeries(statistics.map(_.percentiles), statistics.map(_.mean), sb)

      // y range
      sb.append("&")
      sb.append("chxr=0,0,").append(maxValue).append("|2,0,").append(maxValue)
      sb.append("&")
      sb.append("chds=0,").append(maxValue)
      sb.append("&")

      // grid lines
      appendGridSpacing(maxValue, sb)

      sb.toString
    }
  }

  private def percentileLabels(percentiles: immutable.TreeMap[Int, Long], sb: StringBuilder) {
    sb.append("chxl=1:|")
    val s = percentiles.keys.toList.map(_ + "%").mkString("|")
    sb.append(s)
  }

  private def appendLegend(statistics: immutable.Seq[Stats], sb: StringBuilder, legend: Stats ⇒ String) {
    val legends = statistics.map(legend(_))
    sb.append("chdl=")
    val s = legends.map(urlEncode(_)).mkString("|")
    sb.append(s)
  }

  private def barColors(numberOfSeries: Int, sb: StringBuilder) {
    sb.append("chco=")
    val template = ",A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,A2C180,90AA94,3D7930"
    val s = template.substring(template.length - (numberOfSeries * 7) + 1)
    sb.append(s)
  }

  private def dataSeries(allPercentiles: immutable.Seq[immutable.TreeMap[Int, Long]], meanValues: immutable.Seq[Double], sb: StringBuilder) {
    val percentileSeries =
      for {
        percentiles ← allPercentiles
      } yield {
        percentiles.values.mkString(",")
      }

    val series =
      for ((s, m) ← percentileSeries.zip(meanValues))
        yield s + "," + formatDouble(m)

    sb.append(series.mkString("|"))
  }

  private def dataSeries(values: immutable.Seq[Double], sb: StringBuilder) {
    val series = values.map(formatDouble(_))
    sb.append(series.mkString("|"))
  }

  private def appendGridSpacing(maxValue: Long, sb: StringBuilder) {
    sb.append("chg=0,10")
  }

  private def urlEncode(str: String): String = {
    try {
      URLEncoder.encode(str, "ISO-8859-1")
    } catch {
      case e: UnsupportedEncodingException ⇒ str
    }
  }

  def latencyAndThroughputChartUrl(statistics: immutable.Seq[Stats], title: String): String = {
    if (statistics.isEmpty) ""
    else {
      val sb = new StringBuilder
      sb.append(BaseUrl)
      // line chart
      sb.append("cht=lxy")
      sb.append("&")
      // size
      sb.append("chs=").append(ChartWidth).append("x").append(ChartHeight)
      sb.append("&")
      // title
      sb.append("chtt=").append(urlEncode(title))
      sb.append("&")
      // axis locations
      sb.append("chxt=x,y,r,x,y,r")
      sb.append("&")
      // labels
      sb.append("chxl=3:|clients|4:|Latency+(us)|5:|Throughput+(tps)")
      sb.append("&")
      // label color and font
      sb.append("chxs=0,676767,11.5,0,lt,676767|1,676767,11.5,0,lt,676767|2,676767,11.5,0,lt,676767")
      sb.append("&")
      sb.append("chco=")
      val seriesColors = List("25B33B", "3072F3", "FF0000", "37F0ED", "FF9900")
      sb.append(seriesColors.mkString(","))
      sb.append("&")
      // legend
      sb.append("chdl=5th%20Percentile|Median|95th%20Percentile|Mean|Throughput")
      sb.append("&")

      sb.append("chdlp=b")
      sb.append("&")

      sb.append("chls=1|1|1")
      sb.append("&")

      sb.append("chls=1|1|1")
      sb.append("&")

      // margins
      sb.append("chma=5,5,5,25")
      sb.append("&")

      // data points
      sb.append("chm=")
      val chmStr = seriesColors.zipWithIndex.map(each ⇒ "o," + each._1 + "," + each._2 + ",-1,7").mkString("|")
      sb.append(chmStr)
      sb.append("&")

      // data series
      val loadStr = statistics.map(_.load).mkString(",")
      sb.append("chd=t:")
      val maxP = 95
      val percentiles = List(5, 50, maxP)
      val maxValue = statistics.map(_.percentiles(maxP)).max
      val percentileSeries: List[String] =
        for (p ← percentiles) yield {
          loadStr + "|" + statistics.map(_.percentiles(p)).mkString(",")
        }
      sb.append(percentileSeries.mkString("|"))

      sb.append("|")
      sb.append(loadStr).append("|")
      val meanSeries = statistics.map(s ⇒ formatDouble(s.mean)).mkString(",")
      sb.append(meanSeries)

      sb.append("|")
      val maxTps: Double = statistics.map(_.tps).max
      sb.append(loadStr).append("|")
      val tpsSeries = statistics.map(s ⇒ formatDouble(s.tps)).mkString(",")
      sb.append(tpsSeries)

      val minLoad = statistics.head.load
      val maxLoad = statistics.last.load

      // y range
      sb.append("&")
      sb.append("chxr=0,").append(minLoad).append(",").append(maxLoad).append(",4").append("|1,0,").append(maxValue).append("|2,0,")
        .append(formatDouble(maxTps))
      sb.append("&")

      sb.append("chds=")
      for (p ← percentiles) {
        sb.append(minLoad).append(",").append(maxLoad)
        sb.append(",0,").append(maxValue)
        sb.append(",")
      }
      sb.append(minLoad).append(",").append(maxLoad)
      sb.append(",0,").append(formatDouble(maxValue))
      sb.append(",")
      sb.append(minLoad).append(",").append(maxLoad)
      sb.append(",0,").append(formatDouble(maxTps))
      sb.append("&")

      // label positions
      sb.append("chxp=3,").append("50").append("|4,").append("100").append("|5,").append("100")
      sb.append("&")

      // grid lines
      appendGridSpacing(maxValue, sb)

      sb.toString
    }
  }

  def formatDouble(value: Double): String = {
    new java.math.BigDecimal(value).setScale(2, java.math.RoundingMode.HALF_EVEN).toString
  }

}

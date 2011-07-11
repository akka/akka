package akka.performance.trading.common

import java.io.UnsupportedEncodingException
import java.net.URLEncoder

import scala.collection.immutable.TreeMap

/**
 * Generates URLs to Google Chart API http://code.google.com/apis/chart/
 */
object GoogleChartBuilder {
  val BaseUrl = "http://chart.apis.google.com/chart?"
  val ChartWidth = 750
  val ChartHeight = 400

  /**
   * Builds a bar chart for all percentiles in the statistics.
   */
  def percentilChartUrl(statistics: Seq[Stats], title: String, legend: Stats ⇒ String): String = {
    if (statistics.isEmpty) return ""

    val current = statistics.last

    val sb = new StringBuilder()
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
    dataSeries(statistics.map(_.percentiles), sb)

    // y range
    sb.append("&")
    sb.append("chxr=0,0,").append(maxValue).append("|2,0,").append(maxValue)
    sb.append("&")
    sb.append("chds=0,").append(maxValue)
    sb.append("&")

    // grid lines
    appendGridSpacing(maxValue, sb)

    return sb.toString()
  }

  private def percentileLabels(percentiles: TreeMap[Int, Long], sb: StringBuilder) {
    sb.append("chxl=1:|")
    val s = percentiles.keys.toList.map(_ + "%").mkString("|")
    sb.append(s)
  }

  private def appendLegend(statistics: Seq[Stats], sb: StringBuilder, legend: Stats ⇒ String) {
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

  private def dataSeries(allPercentiles: Seq[TreeMap[Int, Long]], sb: StringBuilder) {
    val series =
      for {
        percentiles ← allPercentiles
      } yield {
        percentiles.values.mkString(",")
      }
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

}
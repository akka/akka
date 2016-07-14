/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity }
import akka.http.scaladsl.{ Http, TestUtils }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.AkkaSpec
import akka.util.ByteString
import org.scalatest.exceptions.TestPendingException
import org.scalatest.concurrent.ScalaFutures

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

class AkkaHttpServerThroughputSpec(config: String) extends AkkaSpec(config)
  with ScalaFutures {

  override def expectedTestDuration = 1.hour

  def this() = this(
    """
      akka {
        test.AkkaHttpServerThroughputSpec {
          rate = 10000
          duration = 30s
          
          totalRequestsFactor = 1.0
        }
      }
    """.stripMargin
  )

  implicit val dispatcher = system.dispatcher
  implicit val mat = ActorMaterializer()

  val MediumByteString = ByteString(Vector.fill(1024)(0.toByte): _*)

  val array_10x: Array[Byte] = Array(Vector.fill(10)(MediumByteString).flatten: _*)
  val array_100x: Array[Byte] = Array(Vector.fill(100)(MediumByteString).flatten: _*)
  val source_10x: Source[ByteString, NotUsed] = Source.repeat(MediumByteString).take(10)
  val source_100x: Source[ByteString, NotUsed] = Source.repeat(MediumByteString).take(100)
  val tenXResponseLength = array_10x.length
  val hundredXResponseLength = array_100x.length

  // format: OFF
  val routes = {
    import Directives._
    
    path("ping") {
      complete("PONG!")
    } ~
    path("long-response-stream" / IntNumber) { n =>
      if (n == 10) complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, array_10x))
      else if (n == 100) complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, array_10x))
      else throw new RuntimeException(s"Not implemented for ${n}")
    } ~
    path("long-response-array" / IntNumber) { n =>
      if (n == 10) complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, source_100x))
      else if (n == 100) complete(HttpEntity(ContentTypes.`text/plain(UTF-8)`, source_100x))
      else throw new RuntimeException(s"Not implemented for ${n}")
    }
  }
  // format: ON

  val (_, hostname, port) = TestUtils.temporaryServerHostnameAndPort()
  val binding = Http().bindAndHandle(routes, hostname, port)

  val totalRequestsFactor = system.settings.config.getDouble("akka.test.AkkaHttpServerThroughputSpec.totalRequestsFactor")
  val requests = Math.round(10000 * totalRequestsFactor)
  val rate = system.settings.config.getInt("akka.test.AkkaHttpServerThroughputSpec.rate")
  val testDuration = system.settings.config.getDuration("akka.test.AkkaHttpServerThroughputSpec.duration", TimeUnit.SECONDS)
  val connections: Long = 10

  // --- urls
  val url_ping = s"http://127.0.0.1:$port/ping"
  def url_longResponseStream(int: Int) = s"http://127.0.0.1:$port/long-response-stream/$int"
  def url_longResponseArray(int: Int) = s"http://127.0.0.1:$port/long-response-array/$int"
  // ---

  "HttpServer" should {
    import scala.sys.process._

    Await.ready(binding, 3.seconds)

    "a warmup" in ifWrk2Available {
      val wrkOptions = s"""-d 30s -R $rate -c $connections -t $connections"""
      s"""wrk $wrkOptions $url_ping""".!!.split("\n")
      info("warmup complete.")
    }

    "have good throughput on PONG response (keep-alive)" in ifWrk2Available {
      val wrkOptions = s"""-d ${testDuration}s -R $rate -c $connections -t $connections --u_latency"""
      val output = s"""wrk $wrkOptions $url_ping""".!!.split("\n")
      infoThe(output)
      printWrkPercentiles(s"Throughput_pong_R:${rate}_C:${connections}_p:", output)
    }
    "have good throughput (ab) (short-lived connections)" in ifAbAvailable {
      val id = s"Throughput_AB-short-lived_pong_R:${rate}_C:${connections}_p:"
      val abOptions = s"-c $connections -n $requests -g $id.tsv -e $id.csv"
      val output = s"""ab $abOptions $url_ping""".!!.split("\n")
      infoThe(output)
      printAbPercentiles(id, output)
    }
    "have good throughput (ab) (long-lived connections)" in ifAbAvailable {
      val id = s"Throughput_AB_pong_shortLived_R:${rate}_C:${connections}_p:"
      val abOptions = s"-c $connections -n $requests -g $id.tsv -e $id.csv"
      info(s"""ab $abOptions $url_ping""")
      val output = s"""ab $abOptions $url_ping""".!!.split("\n")
      infoThe(output)
      printAbPercentiles(s"Throughput_ab_pong_R:${rate}_C:${connections}_p:", output)
    }

    List(
      10 → tenXResponseLength,
      100 → hundredXResponseLength
    ) foreach {
        case (n, lenght) ⇒
          s"have good throughput (streaming-response($lenght), keep-alive)" in {
            val wrkOptions = s"""-d ${testDuration}s -R $rate -c $connections -t $connections --u_latency"""
            val output = s"""wrk $wrkOptions ${url_longResponseStream(n)}""".!!.split("\n")
            infoThe(output)
            printWrkPercentiles(s"Throughput_stream($lenght)_R:${rate}_C:${connections}_p:", output)
          }
          s"have good throughput (array-response($lenght), keep-alive)" in {
            val wrkOptions = s"""-d ${testDuration}s -R $rate -c $connections -t $connections --u_latency"""
            val output = s"""wrk $wrkOptions ${url_longResponseArray(n)}""".!!.split("\n")
            infoThe(output)
            printWrkPercentiles(s"Throughput_array($lenght)_R:${rate}_C:${connections}_p:", output)
          }
      }
  }

  def infoThe(lines: Array[String]): Unit =
    lines.foreach(l ⇒ info("  " + l))

  def printWrkPercentiles(prefix: String, lines: Array[String]): Unit = {
    val percentilesToPrint = 8

    def durationAsMs(d: String): Long = {
      val dd = d.replace("us", "µs") // Scala Duration does not parse "us"
      Duration(dd).toMillis
    }

    var i = 0
    val correctedDistributionStartsHere = lines.zipWithIndex.find(p ⇒ p._1 contains "Latency Distribution").map(_._2).get

    i = correctedDistributionStartsHere + 1 // skip header
    while (i < correctedDistributionStartsHere + 1 + percentilesToPrint) {
      val line = lines(i).trim
      val percentile = line.takeWhile(_ != '%')
      println(prefix + percentile + "_corrected," + durationAsMs(line.drop(percentile.length + 1).trim))
      i += 1
    }

    val uncorrectedDistributionStartsHere = lines.zipWithIndex.find(p ⇒ p._1 contains "Uncorrected Latency").map(_._2).get

    i = uncorrectedDistributionStartsHere + 1 // skip header
    while (i < uncorrectedDistributionStartsHere + 1 + percentilesToPrint) {
      val line = lines(i).trim
      val percentile = line.takeWhile(_ != '%')
      println(prefix + percentile + "_uncorrected," + durationAsMs(line.drop(percentile.length + 1).trim))
      i += 1
    }
  }

  def printAbPercentiles(prefix: String, lines: Array[String]): Unit = {
    val percentilesToPrint = 9

    def durationAsMs(d: String): Long =
      Duration(d).toMillis

    var i = 0
    val correctedDistributionStartsHere = lines.zipWithIndex.find(p ⇒ p._1 contains "Percentage of the requests").map(_._2).get

    i = correctedDistributionStartsHere + 1 // skip header
    while (i < correctedDistributionStartsHere + 1 + percentilesToPrint) {
      val line = lines(i).trim
      val percentile = line.takeWhile(_ != '%')
      println(prefix + percentile + "," + durationAsMs(line.drop(percentile.length + 1).replace("(longest request)", "").trim + "ms"))
      i += 1
    }
  }

  var _ifWrk2Available: Option[Boolean] = None
  @tailrec final def ifWrk2Available(test: ⇒ Unit): Unit = {
    _ifWrk2Available match {
      case Some(false) ⇒ throw new TestPendingException()
      case Some(true)  ⇒ test
      case None ⇒
        import scala.sys.process._

        val wrk = Try("""wrk""".!).getOrElse(-1)
        _ifWrk2Available = Some(wrk == 1) // app found, help displayed
        ifWrk2Available(test)
    }
  }

  var _ifAbAvailable: Option[Boolean] = None
  @tailrec final def ifAbAvailable(test: ⇒ Unit): Unit = {
    _ifAbAvailable match {
      case Some(false) ⇒ throw new TestPendingException()
      case Some(true)  ⇒ test
      case None ⇒
        import scala.sys.process._

        val wrk = Try("""ab -h""".!).getOrElse(-1)
        _ifAbAvailable = Some(wrk == 22) // app found, help displayed (22 return code is when -h runs in ab, weird but true)
        ifAbAvailable(test)
    }
  }

  override protected def beforeTermination(): Unit = {
    binding.futureValue.unbind().futureValue
  }
}

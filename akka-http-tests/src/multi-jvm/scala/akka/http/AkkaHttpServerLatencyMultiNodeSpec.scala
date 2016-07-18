/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http

import java.io.{ BufferedWriter, FileWriter }
import java.util.concurrent.TimeUnit

import akka.NotUsed
import akka.actor.{ Actor, ActorIdentity, ActorRef, Identify, Props }
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model.{ ContentTypes, HttpEntity }
import akka.http.scaladsl.server.{ Directives, Route }
import akka.http.scaladsl.{ Http, TestUtils }
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import akka.testkit.{ ImplicitSender, LongRunningTest }
import akka.util.{ ByteString, Timeout }
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.exceptions.TestPendingException

import scala.annotation.tailrec
import scala.concurrent.duration._
import scala.concurrent.{ Await, Promise }
import scala.util.Try

object AkkaHttpServerLatencyMultiNodeSpec extends MultiNodeConfig {

  commonConfig(ConfigFactory.parseString(
    """
      akka {
        actor.default-mailbox.mailbox-type = "akka.dispatch.UnboundedMailbox"
        actor.provider = "akka.remote.RemoteActorRefProvider"
        stream.materializer.debug.fuzzing-mode = off

        testconductor.barrier-timeout = 20m
        
        test.AkkaHttpServerLatencySpec {
          writeCsv = on # TODO SWITCH BACK
          rate = 10000
          duration = 30s
          
          totalRequestsFactor = 1.0
        }
      }
    """))

  val server = role("server")
  val loadGenerator = role("loadGenerator")

  private var _ifWrk2Available: Option[Boolean] = None
  final def ifWrk2Available(test: ⇒ Unit): Unit =
    if (isWrk2Available) test else throw new TestPendingException() 
  final def isWrk2Available: Boolean = 
    _ifWrk2Available getOrElse {
        import scala.sys.process._
        val wrkExitCode = Try("""wrk""".!).getOrElse(-1)

        _ifWrk2Available = Some(wrkExitCode == 1) // app found, help displayed
        isWrk2Available
    }

  private var _abAvailable: Option[Boolean] = None
  final def ifAbAvailable(test: ⇒ Unit): Unit =
    if (isAbAvailable) test else throw new TestPendingException()
  
  final def isAbAvailable: Boolean = 
    _abAvailable getOrElse {
      import scala.sys.process._
      val abExitCode = Try("""ab -h""".!).getOrElse(-1)
      _abAvailable = Some(abExitCode == 22) // app found, help displayed (22 return code is when -h runs in ab, weird but true)
      isAbAvailable
    }
  
  
  final case class LoadGenCommand(cmd: String)
  final case class LoadGenResults(results: String) {
    def lines = results.split("\n")
  }
  final case class SetServerPort(port: Int)
  class HttpLoadGeneratorActor(serverPort: Promise[Int]) extends Actor {
    override def receive: Receive = {
      case SetServerPort(port) ⇒
        serverPort.success(port)
        context become ready(port)
      case other ⇒
        throw new RuntimeException("No server port known! Initialize with SetServerPort() first! Got: " + other)
    }

    import scala.sys.process._
    def ready(port: Int): Receive = {
      case LoadGenCommand(cmd) if cmd startsWith "wrk" ⇒
        val res = 
          if (isWrk2Available) cmd.!! // blocking. DON'T DO THIS AT HOME, KIDS!
          else "=== WRK NOT AVAILABLE ==="
        sender() ! LoadGenResults(res)
      
      case LoadGenCommand(cmd) if cmd startsWith "ab" ⇒
        val res = 
          if (isAbAvailable) cmd.!! // blocking. DON'T DO THIS AT HOME, KIDS!
          else "=== AB NOT AVAILABLE ==="
        sender() ! LoadGenResults(res)
    }
  }
}

class AkkaHttpServerLatencyMultiNodeSpecMultiJvmNode1 extends AkkaHttpServerLatencyMultiNodeSpec
class AkkaHttpServerLatencyMultiNodeSpecMultiJvmNode2 extends AkkaHttpServerLatencyMultiNodeSpec

class AkkaHttpServerLatencyMultiNodeSpec extends MultiNodeSpec(AkkaHttpServerLatencyMultiNodeSpec) with STMultiNodeSpec
  with ScalaFutures with ImplicitSender {

  import AkkaHttpServerLatencyMultiNodeSpec._

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(10.seconds, interval = 300.millis)

  def initialParticipants = 2

  val MediumByteString = ByteString(Vector.fill(1024)(0.toByte): _*)

  val array_10x: Array[Byte] = Array(Vector.fill(10)(MediumByteString).flatten: _*)
  val array_100x: Array[Byte] = Array(Vector.fill(100)(MediumByteString).flatten: _*)
  val source_10x: Source[ByteString, NotUsed] = Source.repeat(MediumByteString).take(10)
  val source_100x: Source[ByteString, NotUsed] = Source.repeat(MediumByteString).take(100)
  val tenXResponseLength = array_10x.length
  val hundredXResponseLength = array_100x.length
  
  // format: OFF
  val routes: Route = {
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

  val totalRequestsFactor = system.settings.config.getDouble("akka.test.AkkaHttpServerLatencySpec.totalRequestsFactor")
  val requests = Math.round(10000 * totalRequestsFactor)
  val rate = system.settings.config.getInt("akka.test.AkkaHttpServerLatencySpec.rate")
  val testDuration = system.settings.config.getDuration("akka.test.AkkaHttpServerLatencySpec.duration", TimeUnit.SECONDS)
  val connections: Long = 10

  override def binding = _binding
  var _binding: Option[ServerBinding] = None

  val serverPortPromise: Promise[Int] = Promise()
  def serverPort: Int = serverPortPromise.future.futureValue
  def serverHost: String = node(server).address.host.get

  // --- urls
  def url_ping = s"http://$serverHost:$serverPort/ping"
  def url_longResponseStream(int: Int) = s"http://$serverHost:$serverPort/long-response-stream/$int"
  def url_longResponseArray(int: Int) = s"http://$serverHost:$serverPort/long-response-array/$int"
  // ---

  "Akka HTTP" must {
    implicit val dispatcher = system.dispatcher
    implicit val mat = ActorMaterializer()

    "start Akka HTTP" taggedAs LongRunningTest in {
      enterBarrier("startup")

      runOn(loadGenerator) {
        system.actorOf(Props(classOf[HttpLoadGeneratorActor], serverPortPromise), "load-gen")
      }
      enterBarrier("load-gen-ready")

      runOn(server) {
        val (_, _, port) = TestUtils.temporaryServerHostnameAndPort()
        info(s"Binding Akka HTTP Server to port: $port @ ${myself}")
        val futureBinding = Http().bindAndHandle(routes, "0.0.0.0", port)

        _binding = Some(futureBinding.futureValue)
        setServerPort(port)
      }

      enterBarrier("http-server-running")
    }

    "warmup" taggedAs LongRunningTest in ifWrk2Available {
      val id = "warmup"

      val wrkOptions = s"""-d 30s -R $rate -c $connections -t $connections"""
      runLoadTest(id)(s"""wrk $wrkOptions $url_ping""")
    }

    "have good Latency on PONG response (keep-alive)" taggedAs LongRunningTest in ifWrk2Available {
      val id = s"Latency_pong_R:${rate}_C:${connections}_p:"

      val wrkOptions = s"""-d ${testDuration}s -R $rate -c $connections -t $connections --u_latency"""
      runLoadTest(id)(s"""wrk $wrkOptions $url_ping""")
    }

    "have good Latency (ab) (short-lived connections)" taggedAs LongRunningTest in ifAbAvailable {
      val id = s"Latency_AB-short-lived_pong_R:${rate}_C:${connections}_p:"

      val abOptions = s"-c $connections -n $requests"
      runLoadTest(id)(s"""ab $abOptions $url_ping""")
    }

    "have good Latency (ab) (long-lived connections)" taggedAs LongRunningTest in ifAbAvailable {
      val id = s"Latency_AB_pong_long-lived_R:${rate}_C:${connections}_p:"

      val abOptions = s"-c $connections -n $requests -k"
      runLoadTest(id)(s"""ab $abOptions $url_ping""")
    }

    List(
      10 → tenXResponseLength,
      100 → hundredXResponseLength
    ) foreach {
        case (n, lenght) ⇒
          s"have good Latency (streaming-response($lenght), keep-alive)" taggedAs LongRunningTest in {
            val id = s"Latency_stream($lenght)_R:${rate}_C:${connections}_p:"

            val wrkOptions = s"""-d ${testDuration}s -R $rate -c $connections -t $connections --u_latency"""
            runLoadTest(id)(s"""wrk $wrkOptions ${url_longResponseStream(n)}""")
          }
          s"have good Latency (array-response($lenght), keep-alive)" taggedAs LongRunningTest in {
            val id = s"Latency_array($lenght)_R:${rate}_C:${connections}_p:"

            val wrkOptions = s"""-d ${testDuration}s -R $rate -c $connections -t $connections --u_latency"""
            runLoadTest(id)(s"""wrk $wrkOptions ${url_longResponseArray(n)}""")
          }
      }
  }

  def runLoadTest(id: String)(cmd: String) = {
    runOn(loadGenerator) {
      info(s"${id} => running: $cmd")
      import akka.pattern.ask
      implicit val timeout = Timeout(30.minutes) // we don't want to timeout here

      val res = (loadGeneratorActor ? LoadGenCommand(cmd)).mapTo[LoadGenResults]
      val results = Await.result(res, timeout.duration)

      if (id contains "warmup") ()
      else if (cmd startsWith "wrk") printWrkPercentiles(id, results.lines)
      else if (cmd startsWith "ab") printAbPercentiles(id, results.lines)
      else throw new NotImplementedError(s"Unable to handle [$cmd] results!")
    }

    enterBarrier(s"load-test-complete-id:${id}")
  }

  def setServerPort(p: Int): Unit = {
    serverPortPromise.success(p)
    loadGeneratorActor ! SetServerPort(p)
  }

  lazy val loadGeneratorActor: ActorRef = {
    if (isNode(loadGenerator)) {
      system.actorSelection("/user/load-gen") ! Identify(None)
      expectMsgType[ActorIdentity].ref.get
    } else {
      system.actorSelection(node(loadGenerator) / "user" / "load-gen") ! Identify(None)
      expectMsgType[ActorIdentity].ref.get
    }
  }

  private def renderResults(prefix: String, titles: Seq[String], values: Seq[String]): Unit = {
    println("====:" + titles.reverse.map(it ⇒ "\"" + it + "\"").mkString(",") + "\n")
    println("====:" + values.reverse.map(it ⇒ "\"" + it + "\"").mkString(",") + "\n")
  }

  private def durationAsMs(d: String): Long = {
    val dd = d.replace("us", "µs") // Scala Duration does not parse "us"
    val ddd = if (dd endsWith "m") dd.replace("m", " minutes") else dd
    Duration(ddd).toMillis
  }

  private def printWrkPercentiles(prefix: String, lines: Array[String]): Unit = {
    val percentilesToPrint = 8

    var i = 0
    val correctedDistributionStartsHere = lines.zipWithIndex.find(p ⇒ p._1 contains "Latency Distribution").map(_._2).get

    var titles = List.empty[String]
    var metrics = List.empty[String]
    i = correctedDistributionStartsHere + 1 // skip header
    while (i < correctedDistributionStartsHere + 1 + percentilesToPrint) {
      val line = lines(i).trim
      val percentile = line.takeWhile(_ != '%')

      val title = prefix + percentile + "_corrected"
      val duration = durationAsMs(line.drop(percentile.length + 1).trim)

      titles ::= title
      metrics ::= duration.toString
      println(title + "," + duration)

      i += 1
    }
    renderResults(prefix + "_corrected", titles, metrics)

    val uncorrectedDistributionStartsHere = lines.zipWithIndex.find(p ⇒ p._1 contains "Uncorrected Latency").map(_._2).get

    titles = List.empty
    metrics = List.empty
    i = uncorrectedDistributionStartsHere + 1 // skip header
    while (i < uncorrectedDistributionStartsHere + 1 + percentilesToPrint) {
      val line = lines(i).trim
      val percentile = line.takeWhile(_ != '%')

      val title = prefix + percentile + "_uncorrected"
      val duration = durationAsMs(line.drop(percentile.length + 1).trim)

      titles ::= title
      metrics ::= duration.toString
      println(title + "," + duration)

      i += 1
    }
    renderResults(prefix + "_uncorrected", titles, metrics)
  }

  private def printAbPercentiles(prefix: String, lines: Array[String]): Unit = {
    val percentilesToPrint = 9

    var i = 0
    val correctedDistributionStartsHere = lines.zipWithIndex.find(p ⇒ p._1 contains "Percentage of the requests").map(_._2).get

    var titles = List.empty[String]
    var metrics = List.empty[String]
    i = correctedDistributionStartsHere + 1 // skip header
    while (i < correctedDistributionStartsHere + 1 + percentilesToPrint) {
      val line = lines(i).trim
      val percentile = line.takeWhile(_ != '%')
      val title = prefix + percentile
      val duration = durationAsMs(line.drop(percentile.length + 1).replace("(longest request)", "").trim + "ms")

      titles ::= title
      metrics ::= duration.toString
      println(title + "," + duration)

      i += 1
    }
    renderResults(prefix, titles, metrics)
  }

}

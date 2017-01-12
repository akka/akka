package akka.stream.io

import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.TimeoutException

import akka.NotUsed
import com.typesafe.sslconfig.akka.AkkaSSLConfig

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random
import akka.actor.ActorSystem
import akka.pattern.{ after ⇒ later }
import akka.stream._
import akka.stream.TLSProtocol._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit.EventFilter
import akka.util.ByteString
import javax.net.ssl._

import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage

object TlsSpec {

  val rnd = new Random

  def initWithTrust(trustPath: String) = {
    val password = "changeme"

    val keyStore = KeyStore.getInstance(KeyStore.getDefaultType)
    keyStore.load(getClass.getResourceAsStream("/keystore"), password.toCharArray)

    val trustStore = KeyStore.getInstance(KeyStore.getDefaultType)
    trustStore.load(getClass.getResourceAsStream(trustPath), password.toCharArray)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, password.toCharArray)

    val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    trustManagerFactory.init(trustStore)

    val context = SSLContext.getInstance("TLS")
    context.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom)
    context
  }

  def initSslContext(): SSLContext = initWithTrust("/truststore")

  /**
   * This is a stage that fires a TimeoutException failure 2 seconds after it was started,
   * independent of the traffic going through. The purpose is to include the last seen
   * element in the exception message to help in figuring out what went wrong.
   */
  class Timeout(duration: FiniteDuration)(implicit system: ActorSystem) extends GraphStage[FlowShape[ByteString, ByteString]] {

    private val in = Inlet[ByteString]("in")
    private val out = Outlet[ByteString]("out")
    override val shape = FlowShape(in, out)

    override def createLogic(attr: Attributes) = new TimerGraphStageLogic(shape) {
      override def preStart(): Unit = scheduleOnce((), duration)

      var last: ByteString = _
      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          last = grab(in)
          push(out, last)
        }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
      override def onTimer(x: Any): Unit = {
        failStage(new TimeoutException(s"timeout expired, last element was $last"))
      }
    }
  }

}

class TlsSpec extends StreamSpec("akka.loglevel=INFO\nakka.actor.debug.receive=off") {
  import TlsSpec._

  import system.dispatcher
  implicit val materializer = ActorMaterializer()

  import GraphDSL.Implicits._

  val sslConfig: Option[AkkaSSLConfig] = None // no special settings to be applied here

  "SslTls" must {

    val sslContext = initSslContext()

    val debug = Flow[SslTlsInbound].map { x ⇒
      x match {
        case SessionTruncated   ⇒ system.log.debug(s" ----------- truncated ")
        case SessionBytes(_, b) ⇒ system.log.debug(s" ----------- (${b.size}) ${b.take(32).utf8String}")
      }
      x
    }

    val cipherSuites = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA")
    def clientTls(closing: TLSClosing) = TLS(sslContext, None, cipherSuites, Client, closing)
    def badClientTls(closing: TLSClosing) = TLS(initWithTrust("/badtruststore"), None, cipherSuites, Client, closing)
    def serverTls(closing: TLSClosing) = TLS(sslContext, None, cipherSuites, Server, closing)

    trait Named {
      def name: String =
        getClass.getName
          .reverse
          .dropWhile(c ⇒ "$0123456789".indexOf(c) != -1)
          .takeWhile(_ != '$')
          .reverse
    }

    trait CommunicationSetup extends Named {
      def decorateFlow(leftClosing: TLSClosing, rightClosing: TLSClosing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]): Flow[SslTlsOutbound, SslTlsInbound, NotUsed]
      def cleanup(): Unit = ()
    }

    object ClientInitiates extends CommunicationSetup {
      def decorateFlow(leftClosing: TLSClosing, rightClosing: TLSClosing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) =
        clientTls(leftClosing) atop serverTls(rightClosing).reversed join rhs
    }

    object ServerInitiates extends CommunicationSetup {
      def decorateFlow(leftClosing: TLSClosing, rightClosing: TLSClosing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) =
        serverTls(leftClosing) atop clientTls(rightClosing).reversed join rhs
    }

    def server(flow: Flow[ByteString, ByteString, Any]) = {
      val server = Tcp()
        .bind("localhost", 0)
        .to(Sink.foreach(c ⇒ c.flow.join(flow).run()))
        .run()
      Await.result(server, 2.seconds)
    }

    object ClientInitiatesViaTcp extends CommunicationSetup {
      var binding: Tcp.ServerBinding = null
      def decorateFlow(leftClosing: TLSClosing, rightClosing: TLSClosing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) = {
        binding = server(serverTls(rightClosing).reversed join rhs)
        clientTls(leftClosing) join Tcp().outgoingConnection(binding.localAddress)
      }
      override def cleanup(): Unit = binding.unbind()
    }

    object ServerInitiatesViaTcp extends CommunicationSetup {
      var binding: Tcp.ServerBinding = null
      def decorateFlow(leftClosing: TLSClosing, rightClosing: TLSClosing,
                       rhs: Flow[SslTlsInbound, SslTlsOutbound, Any]) = {
        binding = server(clientTls(rightClosing).reversed join rhs)
        serverTls(leftClosing) join Tcp().outgoingConnection(binding.localAddress)
      }
      override def cleanup(): Unit = binding.unbind()
    }

    val communicationPatterns =
      Seq(
        ClientInitiates,
        ServerInitiates,
        ClientInitiatesViaTcp,
        ServerInitiatesViaTcp)

    trait PayloadScenario extends Named {
      def flow: Flow[SslTlsInbound, SslTlsOutbound, Any] =
        Flow[SslTlsInbound]
          .map {
            var session: SSLSession = null
            def setSession(s: SSLSession) = {
              session = s
              system.log.debug(s"new session: $session (${session.getId mkString ","})")
            }

            {
              case SessionTruncated ⇒ SendBytes(ByteString("TRUNCATED"))
              case SessionBytes(s, b) if session == null ⇒
                setSession(s)
                SendBytes(b)
              case SessionBytes(s, b) if s != session ⇒
                setSession(s)
                SendBytes(ByteString("NEWSESSION") ++ b)
              case SessionBytes(s, b) ⇒ SendBytes(b)
            }
          }
      def leftClosing: TLSClosing = IgnoreComplete
      def rightClosing: TLSClosing = IgnoreComplete

      def inputs: immutable.Seq[SslTlsOutbound]
      def output: ByteString

      protected def send(str: String) = SendBytes(ByteString(str))
      protected def send(ch: Char) = SendBytes(ByteString(ch.toByte))
    }

    object SingleBytes extends PayloadScenario {
      val str = "0123456789"
      def inputs = str.map(ch ⇒ SendBytes(ByteString(ch.toByte)))
      def output = ByteString(str)
    }

    object MediumMessages extends PayloadScenario {
      val strs = "0123456789" map (d ⇒ d.toString * (rnd.nextInt(9000) + 1000))
      def inputs = strs map (s ⇒ SendBytes(ByteString(s)))
      def output = ByteString((strs :\ "")(_ ++ _))
    }

    object LargeMessages extends PayloadScenario {
      // TLS max packet size is 16384 bytes
      val strs = "0123456789" map (d ⇒ d.toString * (rnd.nextInt(9000) + 17000))
      def inputs = strs map (s ⇒ SendBytes(ByteString(s)))
      def output = ByteString((strs :\ "")(_ ++ _))
    }

    object EmptyBytesFirst extends PayloadScenario {
      def inputs = List(ByteString.empty, ByteString("hello")).map(SendBytes)
      def output = ByteString("hello")
    }

    object EmptyBytesInTheMiddle extends PayloadScenario {
      def inputs = List(ByteString("hello"), ByteString.empty, ByteString(" world")).map(SendBytes)
      def output = ByteString("hello world")
    }

    object EmptyBytesLast extends PayloadScenario {
      def inputs = List(ByteString("hello"), ByteString.empty).map(SendBytes)
      def output = ByteString("hello")
    }

    // this demonstrates that cancellation is ignored so that the five results make it back
    object CancellingRHS extends PayloadScenario {
      override def flow =
        Flow[SslTlsInbound]
          .mapConcat {
            case SessionTruncated       ⇒ SessionTruncated :: Nil
            case SessionBytes(s, bytes) ⇒ bytes.map(b ⇒ SessionBytes(s, ByteString(b)))
          }
          .take(5)
          .mapAsync(5)(x ⇒ later(500.millis, system.scheduler)(Future.successful(x)))
          .via(super.flow)
      override def rightClosing = IgnoreCancel

      val str = "abcdef" * 100
      def inputs = str.map(send)
      def output = ByteString(str.take(5))
    }

    object CancellingRHSIgnoresBoth extends PayloadScenario {
      override def flow =
        Flow[SslTlsInbound]
          .mapConcat {
            case SessionTruncated       ⇒ SessionTruncated :: Nil
            case SessionBytes(s, bytes) ⇒ bytes.map(b ⇒ SessionBytes(s, ByteString(b)))
          }
          .take(5)
          .mapAsync(5)(x ⇒ later(500.millis, system.scheduler)(Future.successful(x)))
          .via(super.flow)
      override def rightClosing = IgnoreBoth

      val str = "abcdef" * 100
      def inputs = str.map(send)
      def output = ByteString(str.take(5))
    }

    object LHSIgnoresBoth extends PayloadScenario {
      override def leftClosing = IgnoreBoth
      val str = "0123456789"
      def inputs = str.map(ch ⇒ SendBytes(ByteString(ch.toByte)))
      def output = ByteString(str)
    }

    object BothSidesIgnoreBoth extends PayloadScenario {
      override def leftClosing = IgnoreBoth
      override def rightClosing = IgnoreBoth
      val str = "0123456789"
      def inputs = str.map(ch ⇒ SendBytes(ByteString(ch.toByte)))
      def output = ByteString(str)
    }

    object SessionRenegotiationBySender extends PayloadScenario {
      def inputs = List(send("hello"), NegotiateNewSession, send("world"))
      def output = ByteString("helloNEWSESSIONworld")
    }

    // difference is that the RHS engine will now receive the handshake while trying to send
    object SessionRenegotiationByReceiver extends PayloadScenario {
      val str = "abcdef" * 100
      def inputs = str.map(send) ++ Seq(NegotiateNewSession) ++ "hello world".map(send)
      def output = ByteString(str + "NEWSESSIONhello world")
    }

    val logCipherSuite = Flow[SslTlsInbound]
      .map {
        var session: SSLSession = null
        def setSession(s: SSLSession) = {
          session = s
          system.log.debug(s"new session: $session (${session.getId mkString ","})")
        }

        {
          case SessionTruncated ⇒ SendBytes(ByteString("TRUNCATED"))
          case SessionBytes(s, b) if s != session ⇒
            setSession(s)
            SendBytes(ByteString(s.getCipherSuite) ++ b)
          case SessionBytes(s, b) ⇒ SendBytes(b)
        }
      }

    object SessionRenegotiationFirstOne extends PayloadScenario {
      override def flow = logCipherSuite
      def inputs = NegotiateNewSession.withCipherSuites("TLS_RSA_WITH_AES_128_CBC_SHA") :: send("hello") :: Nil
      def output = ByteString("TLS_RSA_WITH_AES_128_CBC_SHAhello")
    }

    object SessionRenegotiationFirstTwo extends PayloadScenario {
      override def flow = logCipherSuite
      def inputs = NegotiateNewSession.withCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA") :: send("hello") :: Nil
      def output = ByteString("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHAhello")
    }

    val scenarios =
      Seq(
        SingleBytes,
        MediumMessages,
        LargeMessages,
        EmptyBytesFirst,
        EmptyBytesInTheMiddle,
        EmptyBytesLast,
        CancellingRHS,
        SessionRenegotiationBySender,
        SessionRenegotiationByReceiver,
        SessionRenegotiationFirstOne,
        SessionRenegotiationFirstTwo)

    for {
      commPattern ← communicationPatterns
      scenario ← scenarios
    } {
      s"work in mode ${commPattern.name} while sending ${scenario.name}" in assertAllStagesStopped {
        val onRHS = debug.via(scenario.flow)
        val f =
          Source(scenario.inputs)
            .via(commPattern.decorateFlow(scenario.leftClosing, scenario.rightClosing, onRHS))
            .via(new SimpleLinearGraphStage[SslTlsInbound] {
              override def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with InHandler with OutHandler {
                setHandlers(in, out, this)

                override def onPush() = push(out, grab(in))
                override def onPull() = pull(in)

                override def onDownstreamFinish() = {
                  system.log.debug("me cancelled")
                  completeStage()
                }
              }
            })
            .via(debug)
            .collect { case SessionBytes(_, b) ⇒ b }
            .scan(ByteString.empty)(_ ++ _)
            .via(new Timeout(6.seconds))
            .dropWhile(_.size < scenario.output.size)
            .runWith(Sink.head)

        Await.result(f, 8.seconds).utf8String should be(scenario.output.utf8String)

        commPattern.cleanup()

        // flush log so as to not mix up logs of different test cases
        if (log.isDebugEnabled)
          EventFilter.debug("stopgap", occurrences = 1) intercept {
            log.debug("stopgap")
          }
      }
    }

    "emit an error if the TLS handshake fails certificate checks" in assertAllStagesStopped {
      val getError = Flow[SslTlsInbound]
        .map[Either[SslTlsInbound, SSLException]](i ⇒ Left(i))
        .recover { case e: SSLException ⇒ Right(e) }
        .collect { case Right(e) ⇒ e }.toMat(Sink.head)(Keep.right)

      val simple = Flow.fromSinkAndSourceMat(getError, Source.maybe[SslTlsOutbound])(Keep.left)

      // The creation of actual TCP connections is necessary. It is the easiest way to decouple the client and server
      // under error conditions, and has the bonus of matching most actual SSL deployments.
      val (server, serverErr) = Tcp()
        .bind("localhost", 0)
        .map(c ⇒ {
          c.flow.joinMat(serverTls(IgnoreBoth).reversed.joinMat(simple)(Keep.right))(Keep.right).run()
        })
        .toMat(Sink.head)(Keep.both).run()

      val clientErr = simple.join(badClientTls(IgnoreBoth))
        .join(Tcp().outgoingConnection(Await.result(server, 1.second).localAddress)).run()

      Await.result(serverErr.flatMap(identity), 1.second).getMessage should include("certificate_unknown")
      Await.result(clientErr, 1.second).getMessage should equal("General SSLEngine problem")
    }

    "reliably cancel subscriptions when TransportIn fails early" in assertAllStagesStopped {
      val ex = new Exception("hello")
      val (sub, out1, out2) =
        RunnableGraph.fromGraph(GraphDSL.create(Source.asSubscriber[SslTlsOutbound], Sink.head[ByteString], Sink.head[SslTlsInbound])((_, _, _)) { implicit b ⇒ (s, o1, o2) ⇒
          val tls = b.add(clientTls(EagerClose))
          s ~> tls.in1; tls.out1 ~> o1
          o2 <~ tls.out2; tls.in2 <~ Source.failed(ex)
          ClosedShape
        }).run()
      the[Exception] thrownBy Await.result(out1, 1.second) should be(ex)
      the[Exception] thrownBy Await.result(out2, 1.second) should be(ex)
      Thread.sleep(500)
      val pub = TestPublisher.probe()
      pub.subscribe(sub)
      pub.expectSubscription().expectCancellation()
    }

    "reliably cancel subscriptions when UserIn fails early" in assertAllStagesStopped {
      val ex = new Exception("hello")
      val (sub, out1, out2) =
        RunnableGraph.fromGraph(GraphDSL.create(Source.asSubscriber[ByteString], Sink.head[ByteString], Sink.head[SslTlsInbound])((_, _, _)) { implicit b ⇒ (s, o1, o2) ⇒
          val tls = b.add(clientTls(EagerClose))
          Source.failed[SslTlsOutbound](ex) ~> tls.in1; tls.out1 ~> o1
          o2 <~ tls.out2; tls.in2 <~ s
          ClosedShape
        }).run()
      the[Exception] thrownBy Await.result(out1, 1.second) should be(ex)
      the[Exception] thrownBy Await.result(out2, 1.second) should be(ex)
      Thread.sleep(500)
      val pub = TestPublisher.probe()
      pub.subscribe(sub)
      pub.expectSubscription().expectCancellation()
    }

    "complete if TLS connection is truncated" in assertAllStagesStopped {

      val ks = KillSwitches.shared("ks")

      val scenario = SingleBytes

      val outFlow = {
        val terminator = BidiFlow.fromFlows(Flow[ByteString], ks.flow[ByteString])
        clientTls(scenario.leftClosing) atop terminator atop serverTls(scenario.rightClosing).reversed join debug.via(scenario.flow) via debug
      }

      val inFlow = Flow[SslTlsInbound]
        .collect { case SessionBytes(_, b) ⇒ b }
        .scan(ByteString.empty)(_ ++ _)
        .via(new Timeout(6.seconds))
        .dropWhile(_.size < scenario.output.size)

      val f =
        Source(scenario.inputs)
          .via(outFlow)
          .via(inFlow)
          .map(result ⇒ {
            ks.shutdown(); result
          })
          .runWith(Sink.last)

      Await.result(f, 8.second).utf8String should be(scenario.output.utf8String)
    }

  }

  "A SslTlsPlacebo" must {

    "pass through data" in {
      val f = Source(1 to 3)
        .map(b ⇒ SendBytes(ByteString(b.toByte)))
        .via(TLSPlacebo() join Flow.apply)
        .grouped(10)
        .runWith(Sink.head)
      val result = Await.result(f, 3.seconds)
      result.map(_.bytes) should be((1 to 3).map(b ⇒ ByteString(b.toByte)))
      result.map(_.session).foreach(s ⇒ s.getCipherSuite should be("SSL_NULL_WITH_NULL_NULL"))
    }

  }

}

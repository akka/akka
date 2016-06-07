/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.NANOSECONDS

import scala.concurrent.duration._

import akka.actor._
import akka.remote.RemoteActorRefProvider
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.serialization.ByteBufferSerializer
import akka.serialization.SerializerWithStringManifest
import akka.testkit._
import com.typesafe.config.ConfigFactory

object MaxThroughputSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       # for serious measurements you should increase the totalMessagesFactor (20)
       akka.test.MaxThroughputSpec.totalMessagesFactor = 1.0
       akka {
         loglevel = ERROR
         # avoid TestEventListener
         loggers = ["akka.event.Logging$$DefaultLogger"]
         testconductor.barrier-timeout = ${barrierTimeout.toSeconds}s
         actor {
           provider = "akka.remote.RemoteActorRefProvider"
           serialize-creators = false
           serialize-messages = false

           serializers {
             test = "akka.remote.artery.MaxThroughputSpec$$TestSerializer"
           }
           serialization-bindings {
             "akka.remote.artery.MaxThroughputSpec$$FlowControl" = test
           }
         }
         remote.artery {
           enabled = on

           # for serious measurements when running this test on only one machine
           # it is recommended to use external media driver
           # See akka-remote-tests/src/test/resources/aeron.properties
           #advanced.embedded-media-driver = off
           #advanced.aeron-dir = "target/aeron"
         }
       }
       """)))

  case object Run
  sealed trait Echo extends DeadLetterSuppression
  final case object Start extends Echo
  final case object End extends Echo
  final case class EndResult(totalReceived: Long)
  final case class FlowControl(burstStartTime: Long) extends Echo

  def receiverProps(reporter: RateReporter, payloadSize: Int): Props =
    Props(new Receiver(reporter, payloadSize)).withDispatcher("akka.remote.default-remote-dispatcher")

  class Receiver(reporter: RateReporter, payloadSize: Int) extends Actor {
    private var c = 0L

    def receive = {
      case msg: Array[Byte] ⇒
        if (msg.length != payloadSize) throw new IllegalArgumentException("Invalid message")
        reporter.onMessage(1, payloadSize)
        c += 1
      case Start ⇒
        c = 0
        sender() ! Start
      case End ⇒
        sender() ! EndResult(c)
        context.stop(self)
      case m: Echo ⇒
        sender() ! m

    }
  }

  def senderProps(target: ActorRef, testSettings: TestSettings, plotRef: ActorRef): Props =
    Props(new Sender(target, testSettings, plotRef))

  class Sender(target: ActorRef, testSettings: TestSettings, plotRef: ActorRef) extends Actor {
    import testSettings._
    val payload = ("0" * testSettings.payloadSize).getBytes("utf-8")
    var startTime = 0L
    var remaining = totalMessages
    var maxRoundTripMillis = 0L

    def receive = {
      case Run ⇒
        // first some warmup
        sendBatch()
        // then Start, which will echo back here
        target ! Start

      case Start ⇒
        println(s"${self.path.name}: Starting benchmark of $totalMessages messages with burst size " +
          s"$burstSize and payload size $payloadSize")
        startTime = System.nanoTime
        remaining = totalMessages
        // have a few batches in flight to make sure there are always messages to send
        (1 to 3).foreach { _ ⇒
          val t0 = System.nanoTime()
          sendBatch()
          sendFlowControl(t0)
        }

      case c @ FlowControl(t0) ⇒
        val now = System.nanoTime()
        val duration = NANOSECONDS.toMillis(now - t0)
        maxRoundTripMillis = math.max(maxRoundTripMillis, duration)

        sendBatch()
        sendFlowControl(now)

      case EndResult(totalReceived) ⇒
        val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
        val throughput = (totalReceived * 1000.0 / took)
        println(
          s"=== MaxThroughput ${self.path.name}: " +
            f"throughput ${throughput * testSettings.senderReceiverPairs}%,.0f msg/s, " +
            f"${throughput * payloadSize * testSettings.senderReceiverPairs}%,.0f bytes/s, " +
            s"dropped ${totalMessages - totalReceived}, " +
            s"max round-trip $maxRoundTripMillis ms, " +
            s"burst size $burstSize, " +
            s"payload size $payloadSize, " +
            s"$took ms to deliver $totalReceived messages")
        plotRef ! PlotResult().add(testName, throughput * payloadSize * testSettings.senderReceiverPairs / 1024 / 1024)
        context.stop(self)
    }

    def sendBatch(): Unit = {
      val batchSize = math.min(remaining, burstSize)
      var i = 0
      while (i < batchSize) {
        //        target ! payload
        target.tell(payload, ActorRef.noSender)
        i += 1
      }
      remaining -= batchSize
    }

    def sendFlowControl(t0: Long): Unit = {
      if (remaining <= 0)
        target ! End
      else
        target ! FlowControl(t0)
    }
  }

  final case class TestSettings(
    testName:            String,
    totalMessages:       Long,
    burstSize:           Int,
    payloadSize:         Int,
    senderReceiverPairs: Int)

  class TestSerializer(val system: ExtendedActorSystem) extends SerializerWithStringManifest with ByteBufferSerializer {

    val FlowControlManifest = "A"

    override val identifier: Int = 100

    override def manifest(o: AnyRef): String =
      o match {
        case _: FlowControl ⇒ FlowControlManifest
      }

    override def toBinary(o: AnyRef, buf: ByteBuffer): Unit =
      o match {
        case FlowControl(burstStartTime) ⇒ buf.putLong(burstStartTime)
      }

    override def fromBinary(buf: ByteBuffer, manifest: String): AnyRef =
      manifest match {
        case FlowControlManifest ⇒ FlowControl(buf.getLong)
      }

    override def toBinary(o: AnyRef): Array[Byte] = ???
    override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = ???
  }

}

class MaxThroughputSpecMultiJvmNode1 extends MaxThroughputSpec
class MaxThroughputSpecMultiJvmNode2 extends MaxThroughputSpec

abstract class MaxThroughputSpec
  extends MultiNodeSpec(MaxThroughputSpec)
  with STMultiNodeSpec with ImplicitSender {

  import MaxThroughputSpec._

  val totalMessagesFactor = system.settings.config.getDouble("akka.test.MaxThroughputSpec.totalMessagesFactor")

  var plot = PlotResult()

  def adjustedTotalMessages(n: Long): Long = (n * totalMessagesFactor).toLong

  override def initialParticipants = roles.size

  def remoteSettings = system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].remoteSettings

  lazy val reporterExecutor = Executors.newFixedThreadPool(1)
  def reporter(name: String): TestRateReporter = {
    val r = new TestRateReporter(name)
    reporterExecutor.execute(r)
    r
  }

  override def afterAll(): Unit = {
    reporterExecutor.shutdown()
    runOn(first) {
      println(plot.csv(system.name))
    }
    super.afterAll()
  }

  def identifyReceiver(name: String, r: RoleName = second): ActorRef = {
    system.actorSelection(node(r) / "user" / name) ! Identify(None)
    expectMsgType[ActorIdentity].ref.get
  }

  val scenarios = List(
    TestSettings(
      testName = "warmup",
      totalMessages = adjustedTotalMessages(20000),
      burstSize = 1000,
      payloadSize = 100,
      senderReceiverPairs = 1),
    TestSettings(
      testName = "1-to-1",
      totalMessages = adjustedTotalMessages(20000),
      burstSize = 1000,
      payloadSize = 100,
      senderReceiverPairs = 1),
    TestSettings(
      testName = "1-to-1-size-1k",
      totalMessages = adjustedTotalMessages(20000),
      burstSize = 1000,
      payloadSize = 1000,
      senderReceiverPairs = 1),
    TestSettings(
      testName = "1-to-1-size-10k",
      totalMessages = adjustedTotalMessages(10000),
      burstSize = 1000,
      payloadSize = 10000,
      senderReceiverPairs = 1),
    TestSettings(
      testName = "5-to-5",
      totalMessages = adjustedTotalMessages(20000),
      burstSize = 200, // don't exceed the send queue capacity 200*5*3=3000
      payloadSize = 100,
      senderReceiverPairs = 5))

  def test(testSettings: TestSettings): Unit = {
    import testSettings._
    val receiverName = testName + "-rcv"

    runOn(second) {
      val rep = reporter(testName)
      for (n ← 1 to senderReceiverPairs) {
        val receiver = system.actorOf(receiverProps(rep, payloadSize), receiverName + n)
      }
      enterBarrier(receiverName + "-started")
      enterBarrier(testName + "-done")
      rep.halt()
    }

    runOn(first) {
      enterBarrier(receiverName + "-started")
      val ignore = TestProbe()
      val senders = for (n ← 1 to senderReceiverPairs) yield {
        val receiver = identifyReceiver(receiverName + n)
        val plotProbe = TestProbe()
        val snd = system.actorOf(
          senderProps(receiver, testSettings, plotProbe.ref),
          testName + "-snd" + n)
        val terminationProbe = TestProbe()
        terminationProbe.watch(snd)
        snd ! Run
        (snd, terminationProbe, plotProbe)
      }
      senders.foreach {
        case (snd, terminationProbe, plotProbe) ⇒
          if (snd == senders.head._1) {
            terminationProbe.expectTerminated(snd, barrierTimeout)
            val plotResult = plotProbe.expectMsgType[PlotResult]
            plot = plot.addAll(plotResult)
          } else
            terminationProbe.expectTerminated(snd, 10.seconds)
      }
      enterBarrier(testName + "-done")
    }

    enterBarrier("after-" + testName)
  }

  "Max throughput of Artery" must {

    for (s ← scenarios) {
      s"be great for ${s.testName}, burstSize = ${s.burstSize}, payloadSize = ${s.payloadSize}" in test(s)
    }

  }
}

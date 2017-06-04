/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.NANOSECONDS

import scala.concurrent.duration._
import akka.actor._
import akka.remote.{ RARP, RemoteActorRefProvider, RemotingMultiNodeSpec }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.PerfFlamesSupport
import akka.remote.testkit.STMultiNodeSpec
import akka.serialization.ByteBufferSerializer
import akka.serialization.SerializerWithStringManifest
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.remote.artery.compress.CompressionProtocol.Events.ReceivedActorRefCompressionTable
import akka.remote.artery.MaxThroughputSpec._

object FanOutThroughputSpec extends MultiNodeConfig {
  val totalNumberOfNodes =
    System.getProperty("MultiJvm.akka.test.FanOutThroughputSpec.nrOfNodes") match {
      case null  ⇒ 4
      case value ⇒ value.toInt
    }
  val senderReceiverPairs = totalNumberOfNodes - 1

  for (n ← 1 to totalNumberOfNodes) role("node-" + n)

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       # for serious measurements you should increase the totalMessagesFactor (20)
       akka.test.FanOutThroughputSpec.totalMessagesFactor = 10.0
       akka.test.FanOutThroughputSpec.real-message = off
       """))
    .withFallback(MaxThroughputSpec.cfg)
    .withFallback(RemotingMultiNodeSpec.commonConfig))

}

class FanOutThroughputSpecMultiJvmNode1 extends FanOutThroughputSpec
class FanOutThroughputSpecMultiJvmNode2 extends FanOutThroughputSpec
class FanOutThroughputSpecMultiJvmNode3 extends FanOutThroughputSpec
class FanOutThroughputSpecMultiJvmNode4 extends FanOutThroughputSpec
//class FanOutThroughputSpecMultiJvmNode5 extends FanOutThroughputSpec
//class FanOutThroughputSpecMultiJvmNode6 extends FanOutThroughputSpec
//class FanOutThroughputSpecMultiJvmNode7 extends FanOutThroughputSpec

abstract class FanOutThroughputSpec extends RemotingMultiNodeSpec(FanOutThroughputSpec) with PerfFlamesSupport {

  import FanOutThroughputSpec._

  val totalMessagesFactor = system.settings.config.getDouble("akka.test.FanOutThroughputSpec.totalMessagesFactor")
  val realMessage = system.settings.config.getBoolean("akka.test.FanOutThroughputSpec.real-message")

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
    runOn(roles.head) {
      println(plot.csv(system.name))
    }
    super.afterAll()
  }

  def identifyReceiver(name: String, r: RoleName): ActorRef = {
    system.actorSelection(node(r) / "user" / name) ! Identify(None)
    expectMsgType[ActorIdentity](10.seconds).ref.get
  }

  val burstSize = 2000 / senderReceiverPairs
  val scenarios = List(
    TestSettings(
      testName = "warmup",
      totalMessages = adjustedTotalMessages(20000),
      burstSize = burstSize,
      payloadSize = 100,
      senderReceiverPairs = senderReceiverPairs,
      realMessage),
    TestSettings(
      testName = "size-100",
      totalMessages = adjustedTotalMessages(50000),
      burstSize = burstSize,
      payloadSize = 100,
      senderReceiverPairs = senderReceiverPairs,
      realMessage),
    TestSettings(
      testName = "size-1k",
      totalMessages = adjustedTotalMessages(20000),
      burstSize = burstSize,
      payloadSize = 1000,
      senderReceiverPairs = senderReceiverPairs,
      realMessage),
    TestSettings(
      testName = "size-10k",
      totalMessages = adjustedTotalMessages(10000),
      burstSize = burstSize,
      payloadSize = 10000,
      senderReceiverPairs = senderReceiverPairs,
      realMessage))

  def test(testSettings: TestSettings, resultReporter: BenchmarkFileReporter): Unit = {
    import testSettings._
    val receiverName = testName + "-rcv"

    val targetNodes = roles.tail

    runPerfFlames(roles: _*)(delay = 5.seconds, time = 15.seconds)

    runOn(targetNodes: _*) {
      val rep = reporter(testName)
      val receiver = system.actorOf(
        receiverProps(rep, payloadSize, printTaskRunnerMetrics = true, senderReceiverPairs),
        receiverName)
      enterBarrier(receiverName + "-started")
      enterBarrier(testName + "-done")
      receiver ! PoisonPill
      rep.halt()
    }

    runOn(roles.head) {
      enterBarrier(receiverName + "-started")
      val ignore = TestProbe()
      val receivers = targetNodes.map(target ⇒ identifyReceiver(receiverName, target)).toArray[ActorRef]
      val senders = for ((target, i) ← targetNodes.zipWithIndex) yield {
        val receiver = receivers(i)
        val plotProbe = TestProbe()
        val snd = system.actorOf(
          senderProps(receiver, receivers, testSettings, plotProbe.ref, printTaskRunnerMetrics = i == 0, resultReporter),
          testName + "-snd" + (i + 1))
        val terminationProbe = TestProbe()
        terminationProbe.watch(snd)
        snd ! Run
        (snd, terminationProbe, plotProbe)
      }
      senders.foreach {
        case (snd, terminationProbe, plotProbe) ⇒
          terminationProbe.expectTerminated(snd, barrierTimeout)
          if (snd == senders.head._1) {
            val plotResult = plotProbe.expectMsgType[PlotResult]
            plot = plot.addAll(plotResult)
          }
      }
      enterBarrier(testName + "-done")
    }

    enterBarrier("after-" + testName)
  }

  "Max throughput of fan-out" must {
    val reporter = BenchmarkFileReporter("FanOutThroughputSpec", system)
    for (s ← scenarios) {
      s"be great for ${s.testName}, burstSize = ${s.burstSize}, payloadSize = ${s.payloadSize}" in test(s, reporter)
    }
  }
}

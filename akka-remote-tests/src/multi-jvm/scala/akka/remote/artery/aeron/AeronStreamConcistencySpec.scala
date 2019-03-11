/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery
package aeron

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

import akka.Done
import akka.actor.ExtendedActorSystem
import akka.actor.Props
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.stream.ActorMaterializer
import akka.stream.KillSwitches
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Source
import akka.testkit._
import akka.util.ByteString
import com.typesafe.config.ConfigFactory
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import org.agrona.IoUtil

object AeronStreamConsistencySpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
       akka {
         loglevel = INFO
         actor {
           provider = remote
         }
         remote.artery.enabled = off
       }
       """)))
}

class AeronStreamConsistencySpecMultiJvmNode1 extends AeronStreamConsistencySpec
class AeronStreamConsistencySpecMultiJvmNode2 extends AeronStreamConsistencySpec

abstract class AeronStreamConsistencySpec
    extends MultiNodeSpec(AeronStreamConsistencySpec)
    with STMultiNodeSpec
    with ImplicitSender {

  import AeronStreamConsistencySpec._

  val driver = MediaDriver.launchEmbedded()

  val aeron = {
    val ctx = new Aeron.Context
    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  val idleCpuLevel = system.settings.config.getInt("akka.remote.artery.advanced.idle-cpu-level")
  val taskRunner = {
    val r = new TaskRunner(system.asInstanceOf[ExtendedActorSystem], idleCpuLevel)
    r.start()
    r
  }

  val pool = new EnvelopeBufferPool(1024 * 1024, 128)

  lazy implicit val mat = ActorMaterializer()(system)
  import system.dispatcher

  override def initialParticipants = roles.size

  def channel(roleName: RoleName) = {
    val n = node(roleName)
    system.actorSelection(n / "user" / "updPort") ! UdpPortActor.GetUdpPort
    val port = expectMsgType[Int]
    s"aeron:udp?endpoint=${n.address.host.get}:$port"
  }

  val streamId = 1
  val giveUpMessageAfter = 30.seconds

  override def afterAll(): Unit = {
    taskRunner.stop()
    aeron.close()
    driver.close()
    IoUtil.delete(new File(driver.aeronDirectoryName), true)
    super.afterAll()
  }

  "Message consistency of Aeron Streams" must {

    "start upd port" in {
      system.actorOf(Props[UdpPortActor], "updPort")
      enterBarrier("udp-port-started")
    }

    "start echo" in {
      runOn(second) {
        // just echo back
        Source
          .fromGraph(new AeronSource(channel(second), streamId, aeron, taskRunner, pool, IgnoreEventSink, 0))
          .runWith(
            new AeronSink(channel(first), streamId, aeron, taskRunner, pool, giveUpMessageAfter, IgnoreEventSink))
      }
      enterBarrier("echo-started")
    }

    "deliver messages in order without loss" in {
      runOn(first) {
        val totalMessages = 50000
        val count = new AtomicInteger
        val done = TestLatch(1)
        val killSwitch = KillSwitches.shared("test")
        val started = TestProbe()
        val startMsg = "0".getBytes("utf-8")
        Source
          .fromGraph(new AeronSource(channel(first), streamId, aeron, taskRunner, pool, IgnoreEventSink, 0))
          .via(killSwitch.flow)
          .runForeach { envelope =>
            val bytes = ByteString.fromByteBuffer(envelope.byteBuffer)
            if (bytes.length == 1 && bytes(0) == startMsg(0))
              started.ref ! Done
            else {
              val c = count.incrementAndGet()
              val x = new String(bytes.toArray, "utf-8").toInt
              if (x != c) {
                throw new IllegalArgumentException(s"# wrong message $x expected $c")
              }
              if (c == totalMessages)
                done.countDown()
            }
            pool.release(envelope)
          }
          .failed
          .foreach { _.printStackTrace }

        within(10.seconds) {
          Source(1 to 100)
            .map { _ =>
              val envelope = pool.acquire()
              envelope.byteBuffer.put(startMsg)
              envelope.byteBuffer.flip()
              envelope
            }
            .throttle(1, 200.milliseconds, 1, ThrottleMode.Shaping)
            .runWith(
              new AeronSink(channel(second), streamId, aeron, taskRunner, pool, giveUpMessageAfter, IgnoreEventSink))
          started.expectMsg(Done)
        }

        Source(1 to totalMessages)
          .throttle(10000, 1.second, 1000, ThrottleMode.Shaping)
          .map { n =>
            val envelope = pool.acquire()
            envelope.byteBuffer.put(n.toString.getBytes("utf-8"))
            envelope.byteBuffer.flip()
            envelope
          }
          .runWith(
            new AeronSink(channel(second), streamId, aeron, taskRunner, pool, giveUpMessageAfter, IgnoreEventSink))

        Await.ready(done, 20.seconds)
        killSwitch.shutdown()
      }
      enterBarrier("after-1")
    }

  }
}

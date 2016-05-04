/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.Done
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.stream.ActorMaterializer
import akka.stream.KillSwitches
import akka.stream.ThrottleMode
import akka.stream.scaladsl.Source
import akka.testkit._
import com.typesafe.config.ConfigFactory
import io.aeron.Aeron
import io.aeron.driver.MediaDriver
import akka.actor.ExtendedActorSystem

object AeronStreamConsistencySpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  val barrierTimeout = 5.minutes

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString(s"""
       akka {
         loglevel = INFO
         actor {
           provider = "akka.remote.RemoteActorRefProvider"
         }
         remote.artery.enabled = off
       }
       """)))

  def aeronPort(roleName: RoleName): Int =
    roleName match {
      case `first`  ⇒ 20521 // TODO yeah, we should have support for dynamic port assignment
      case `second` ⇒ 20522
    }

}

class AeronStreamConsistencySpecMultiJvmNode1 extends AeronStreamConsistencySpec
class AeronStreamConsistencySpecMultiJvmNode2 extends AeronStreamConsistencySpec

abstract class AeronStreamConsistencySpec
  extends MultiNodeSpec(AeronStreamConsistencySpec)
  with STMultiNodeSpec with ImplicitSender {

  import AeronStreamConsistencySpec._

  val driver = MediaDriver.launchEmbedded()

  val aeron = {
    val ctx = new Aeron.Context
    ctx.aeronDirectoryName(driver.aeronDirectoryName)
    Aeron.connect(ctx)
  }

  val taskRunner = {
    val r = new TaskRunner(system.asInstanceOf[ExtendedActorSystem])
    r.start()
    r
  }

  lazy implicit val mat = ActorMaterializer()(system)
  import system.dispatcher

  override def initialParticipants = roles.size

  def channel(roleName: RoleName) = {
    val a = node(roleName).address
    s"aeron:udp?endpoint=${a.host.get}:${aeronPort(roleName)}"
  }

  val streamId = 1

  override def afterAll(): Unit = {
    taskRunner.stop()
    aeron.close()
    driver.close()
    super.afterAll()
  }

  "Message consistency of Aeron Streams" must {

    "start echo" in {
      runOn(second) {
        // just echo back
        Source.fromGraph(new AeronSource(channel(second), streamId, aeron, taskRunner))
          .runWith(new AeronSink(channel(first), streamId, aeron, taskRunner))
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
        Source.fromGraph(new AeronSource(channel(first), streamId, aeron, taskRunner))
          .via(killSwitch.flow)
          .runForeach { bytes ⇒
            if (bytes.length == 1 && bytes(0) == startMsg(0))
              started.ref ! Done
            else {
              val c = count.incrementAndGet()
              val x = new String(bytes, "utf-8").toInt
              if (x != c) {
                throw new IllegalArgumentException(s"# wrong message $x expected $c")
              }
              if (c == totalMessages)
                done.countDown()
            }
          }.onFailure {
            case e ⇒ e.printStackTrace
          }

        within(10.seconds) {
          Source(1 to 100).map(_ ⇒ startMsg)
            .throttle(1, 200.milliseconds, 1, ThrottleMode.Shaping)
            .runWith(new AeronSink(channel(second), streamId, aeron, taskRunner))
          started.expectMsg(Done)
        }

        Source(1 to totalMessages)
          .throttle(10000, 1.second, 1000, ThrottleMode.Shaping)
          .map { n ⇒ n.toString.getBytes("utf-8") }
          .runWith(new AeronSink(channel(second), streamId, aeron, taskRunner))

        Await.ready(done, 20.seconds)
        killSwitch.shutdown()
      }
      enterBarrier("after-1")
    }

  }
}

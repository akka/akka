/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.ActorRef
import akka.aeron.scaladsl.AeronSinkSpec.FragmentProbe
import akka.aeron.AeronExtension
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Keep, Sink }
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.{ AkkaSpec, SocketUtil, TestProbe }
import akka.util.ByteString
import io.aeron.logbuffer.{ FragmentHandler, Header }
import io.aeron.{ FragmentAssembler, Subscription }
import org.agrona.DirectBuffer

import scala.concurrent.Future
import scala.concurrent.duration._

object AeronSinkSpec {

  class FragmentProbe(testProbe: TestProbe) extends FragmentAssembler(new FragmentHandler {
    override def onFragment(aeronBuffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
      val dst = new Array[Byte](length)
      aeronBuffer.getBytes(offset, dst)
      testProbe.ref.tell(ByteString(dst), ActorRef.noSender)
    }
  })
}

case class TestSubscription(channel: String, streamId: Int, responseProbe: TestProbe, sub: Subscription) {

  private val fragmentHandler = new FragmentProbe(responseProbe)

  def poll(): Int = {
    val result = sub.poll(fragmentHandler, streamId)
    if (result < 0) {
      println("Failed to poll: " + result)
    }
    result
  }

}

class AeronSinkSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()
  val streamIds = new AtomicInteger(1)

  def aeronChannelWithSubscription(): TestSubscription = {
    val streamId = streamIds.getAndIncrement()
    val port = SocketUtil.temporaryLocalPort(udp = true)
    val channel = s"aeron:udp?endpoint=localhost:$port"
    val subscriber = AeronExtension(system).aeron.addSubscription(channel, streamId)
    val responseProbe = TestProbe()
    TestSubscription(channel, streamId, responseProbe, subscriber)
  }

  "AeronSink" should {
    "publish message" in {
      val testSub = aeronChannelWithSubscription()
      val sourceProbe = TestSource.probe[ByteString]
      val sink: Sink[ByteString, Future[Done]] = AeronSink(system, AeronSinkSettings(testSub.channel, testSub.streamId, 1.second))
      val inputProbe = sourceProbe.toMat(sink)(Keep.left).run()
      inputProbe.sendNext(ByteString("cats"))
      awaitAssert {
        testSub.poll() shouldEqual 1
      }
      testSub.responseProbe.expectMsg(ByteString("cats"))

    }

    "publish many messages" in {
      val testSub = aeronChannelWithSubscription()
      val sourceProbe = TestSource.probe[ByteString]
      val sink = AeronSink(system, AeronSinkSettings(testSub.channel, testSub.streamId, 1.second))
      val inputProbe = sourceProbe.toMat(sink)(Keep.left).run()

      val nrMessages = 20
      (1 until nrMessages) foreach { i ⇒
        inputProbe.sendNext(ByteString(s"$i"))
        awaitAssert {
          val fragments = testSub.poll()
          fragments shouldEqual 1
        }
        testSub.responseProbe.expectMsg(ByteString(s"$i"))
      }
    }

    "fragmented messages" in {
      pending
    }
  }
}

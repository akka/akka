/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.scaladsl

import java.util.concurrent.atomic.AtomicInteger

import akka.aeron.AeronExtension
import akka.stream.ActorMaterializer
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.{ AkkaSpec, SocketUtil }
import akka.util.ByteString
import io.aeron.Publication
import org.agrona.BufferUtil
import org.agrona.concurrent.UnsafeBuffer

case class TestPublication(channel: String, streamId: Int, pub: Publication)

class AeronSourceSpec extends AkkaSpec {

  val sendBuffer: UnsafeBuffer = new UnsafeBuffer(BufferUtil.allocateDirectAligned(256, 64))

  implicit val materializer = ActorMaterializer()
  val streamIds = new AtomicInteger(1)

  def aeronChannelWithPublication(): TestPublication = {
    val streamId = streamIds.getAndIncrement()
    val port = SocketUtil.temporaryLocalPort(udp = true)
    val channel = s"aeron:udp?endpoint=localhost:$port"
    val pub = AeronExtension(system).aeron.addPublication(channel, streamId)
    TestPublication(channel, streamId, pub)
  }

  "AeronSource" should {

    "read message" in {
      val testPub = aeronChannelWithPublication()
      val source = AeronSource(system, AeronSourceSettings(testPub.channel, testPub.streamId, spinning = 5))
      val testSink = TestSink.probe[ByteString](system)
      val outProbe = source.runWith(testSink)
      val msg = "hello"
      sendBuffer.putBytes(0, msg.getBytes())
      awaitAssert {
        val result = testPub.pub.offer(sendBuffer, 0, msg.length)
        assert(result > 0)
      }

      outProbe.requestNext() shouldEqual ByteString(msg)
    }

    "read many messages one by one" in {
      val testPub = aeronChannelWithPublication()
      val source = AeronSource(system, AeronSourceSettings(testPub.channel, testPub.streamId, spinning = 5))
      val testSink = TestSink.probe[ByteString](system)
      val outProbe = source.runWith(testSink)
      val nrMessages = 100
      (0 until nrMessages) foreach { i ⇒
        val msg = s"$i"
        sendBuffer.putBytes(0, msg.getBytes())
        awaitAssert {
          val result = testPub.pub.offer(sendBuffer, 0, msg.length)
          assert(result > 0)
        }
        val msgBack = outProbe.requestNext()
        msgBack shouldEqual ByteString(msg)
      }
    }

    "read many messages in bulk" in {
      pending

    }

    "fragmented messages" in {
      pending
    }
  }

}

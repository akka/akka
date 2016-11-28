/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.driver

import java.net.InetSocketAddress

import akka.NotUsed
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.Promise

class UdpDriverSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()
  val address1 = new InetSocketAddress("localhost", 8000)
  val address2 = new InetSocketAddress("localhost", 8001)

  val driver1 = new UdpDriver(address1)
  val driver2 = new UdpDriver(address2)
  driver1.start()
  println("starting 2")
  driver2.start()

  val transport1 = new UdpTransport(driver1)
  val transport2 = new UdpTransport(driver2)

  val sink: Sink[Frame, NotUsed] = transport1.forRemoteAddress(address2).to(Sink.ignore)
  val source: Source[Frame, Any] = Source.maybe[Frame].via(transport2.forRemoteAddress(address1))

  val frameBuffer1 = new FrameBuffer(driver1.bufferPool)
  val frameBuffer2 = new FrameBuffer(driver2.bufferPool)

  "Udp transport" must {

    "work in the happy case" in {
      val frame = frameBuffer1.acquire()
      frame.writeByteString(ByteString("Hello"))

      val result = source.take(1).map { frame ⇒
        frame.buffer.flip()
        val bs = frame.toByteString
        frame.release()
        bs
      }.runWith(Sink.seq)

      Source.single(frame).runWith(sink)

      result.futureValue should ===(List(ByteString("Hello")))

      frame.release()
    }

    "work with a longer sequence" in {
      // Cannot send more bytes without releasing frames
      val Count = 32

      val testData = List.tabulate(Count)(i ⇒ ByteString.fromArray(Array.fill[Byte](i + 1)(i.toByte)))
      val testFrames = testData.map { bs ⇒
        val frame = frameBuffer1.acquire()
        frame.writeByteString(bs)
        frame
      }

      val result = source.take(Count).map { frame ⇒
        frame.buffer.flip()
        val bs = frame.toByteString
        frame.release()
        bs
      }.runWith(Sink.seq)

      Source(testFrames).runWith(sink)
      result.futureValue should ===(testData)

      testFrames.foreach(_.release())
    }

    "reuse registration after first received bytes to send" in {
      val Count = 16

      val testData = List.tabulate(Count)(i ⇒ ByteString.fromArray(Array.fill[Byte](i + 1)(i.toByte)))
      val testFrames1 = testData.map { bs ⇒
        val frame = frameBuffer1.acquire()
        frame.writeByteString(bs)
        frame
      }

      val testFrames2 = testData.map { bs ⇒
        val frame = frameBuffer1.acquire()
        frame.writeByteString(bs)
        frame
      }

      // This receive will be fulfilled later
      val (writePromise2, result2) = Source.maybe[immutable.Seq[Frame]]
        .mapConcat(identity)
        .via(transport2.forRemoteAddress(address1))
        .take(Count)
        .map { frame ⇒
          frame.buffer.flip()
          val bs = frame.toByteString
          frame.release()
          bs
        }
        .toMat(Sink.seq)(Keep.both)
        .run()

      val result1 = Source(testFrames1)
        .via(transport1.forRemoteAddress(address2))
        .take(Count)
        .map { frame ⇒
          frame.buffer.flip()
          val bs = frame.toByteString
          frame.release()
          bs
        }
        .runWith(Sink.seq)

      result2.futureValue should ===(testData)

      writePromise2.success(Some(testFrames2))
      result1.futureValue should ===(testData)

    }

    "work with many messages if flow control is added" in {
      val Count = 10000
      val testPayload = ByteString("Hello")

      val result = Source.maybe[ByteString]
        .via(FlowAndLossControl(frameBuffer2) join transport2.forRemoteAddress(address1)).map { bs ⇒
          //println(bs)
          bs
        }
        .take(Count)
        .runWith(Sink.seq)

      Source.repeat(testPayload)
        .take(Count)
        .via(FlowAndLossControl(frameBuffer1) join transport1.forRemoteAddress(address2))
        .runWith(Sink.ignore)

      result.futureValue should ===(List.fill(Count)(testPayload))

    }
  }

  override def afterTermination(): Unit = {
    driver1.stop()
    driver2.stop()
  }
}

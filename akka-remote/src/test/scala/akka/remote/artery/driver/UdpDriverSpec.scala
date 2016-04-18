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

  val frameBuffer = new FrameBuffer(driver1.bufferPool)

  "Udp transport" must {

    "work in the happy case" in {
      val frame = frameBuffer.aquire()
      frame.putByteString(ByteString("Hello"))

      val result = source.take(1).map { frame ⇒
        frame.buffer.flip()
        val bs = frame.toByteString
        frame.release()
        println("GOT " + bs)
        bs
      }.runWith(Sink.seq)

      Source.single(frame).runWith(sink)

      result.futureValue should ===(List(ByteString("Hello")))

    }

  }

  override def afterTermination(): Unit = {
    driver1.stop()
    driver2.stop()
  }
}

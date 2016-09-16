/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.ActorRef
import akka.testkit.{ AkkaSpec, TestProbe }
import akka.util.ByteString
import scala.concurrent.duration._

class MetadataContainerSpec extends AkkaSpec {

  "MetadataContainer" should {
    "parse, given empty map" in {
      val map = new MetadataMap[ByteString]
      val container = new MetadataMapRendering(map)

      val rendered = container.render()
      val back = MetadataMapParsing.parseRaw(rendered.asByteBuffer)

      map.toString() should ===(back.metadataMap.toString())
    }
    "parse, given 1 allocated in map" in {
      val map = new MetadataMap[ByteString]
      val container = new MetadataMapRendering(map)
      map.set(1, ByteString("!!!"))

      val rendered = container.render()
      val back = MetadataMapParsing.parseRaw(rendered.asByteBuffer)

      map.toString() should ===(back.metadataMap.toString())
    }

    "apply, given 3 allocated in map" in {
      val map = new MetadataMap[ByteString]
      val container = new MetadataMapRendering(map)
      map.set(1, ByteString("!!!"))
      map.set(10, ByteString("??????"))
      map.set(31, ByteString("........."))

      val p = TestProbe()

      def testInstrument(id: Int): RemoteInstrument = {
        new RemoteInstrument {
          override def identifier: Byte = id.toByte
          override def remoteMessageSent(recipient: ActorRef, message: Object, sender: ActorRef): ByteString = ???
          override def remoteMessageReceived(recipient: ActorRef, message: Object, sender: ActorRef, metadata: ByteString): Unit =
            p.ref ! s"${identifier}-${metadata.utf8String}"
        }
      }
      val instruments = Vector(
        testInstrument(1), testInstrument(31), testInstrument(10)
      )

      val rendered = container.render()

      val mockEnvelope = new ReusableInboundEnvelope
      MetadataMapParsing.applyAllRemoteMessageReceivedRaw(instruments, mockEnvelope, rendered.asByteBuffer)

      p.expectMsgAllOf("1-!!!", "10-??????", "31-.........")
      p.expectNoMsg(100.millis)
    }

  }
}

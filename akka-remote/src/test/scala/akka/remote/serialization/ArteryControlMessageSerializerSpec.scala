/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor._
import akka.remote.UniqueAddress
import akka.remote.artery.OutboundHandshake.{ HandshakeReq, HandshakeRsp }
import akka.remote.artery.compress.CompressionProtocol.{ ActorRefCompressionAdvertisement, ActorRefCompressionAdvertisementAck, ClassManifestCompressionAdvertisement, ClassManifestCompressionAdvertisementAck }
import akka.remote.artery.compress.CompressionTable
import akka.remote.artery.{ ActorSystemTerminating, ActorSystemTerminatingAck, Quarantined, SystemMessageDelivery }
import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec

class ArteryControlMessageSerializerSpec extends AkkaSpec {
  "ArteryControlMessageSerializer" must {
    val actorA = system.actorOf(Props(new Actor { def receive = PartialFunction.empty }))
    val actorB = system.actorOf(Props(new Actor { def receive = PartialFunction.empty }))

    Seq(
      "Quarantined" → Quarantined(uniqueAddress(), uniqueAddress()),
      "ActorSystemTerminating" → ActorSystemTerminating(uniqueAddress()),
      "ActorSystemTerminatingAck" → ActorSystemTerminatingAck(uniqueAddress()),
      "HandshakeReq" → HandshakeReq(uniqueAddress()),
      "HandshakeRsp" → HandshakeRsp(uniqueAddress()),
      "ActorRefCompressionAdvertisement" → ActorRefCompressionAdvertisement(uniqueAddress(), CompressionTable(123, Map(actorA → 123, actorB → 456))),
      "ActorRefCompressionAdvertisementAck" → ActorRefCompressionAdvertisementAck(uniqueAddress(), 23),
      "ClassManifestCompressionAdvertisement" → ClassManifestCompressionAdvertisement(uniqueAddress(), CompressionTable(42, Map("a" → 535, "b" → 23))),
      "ClassManifestCompressionAdvertisementAck" → ClassManifestCompressionAdvertisementAck(uniqueAddress(), 23),
      "SystemMessageDelivery.SystemMessageEnvelop" → SystemMessageDelivery.SystemMessageEnvelope("test", 1234567890123L, uniqueAddress()),
      "SystemMessageDelivery.Ack" → SystemMessageDelivery.Ack(98765432109876L, uniqueAddress()),
      "SystemMessageDelivery.Nack" → SystemMessageDelivery.Nack(98765432109876L, uniqueAddress())
    ).foreach {
        case (scenario, item) ⇒
          s"resolve serializer for $scenario" in {
            val serializer = SerializationExtension(system)
            serializer.serializerFor(item.getClass).getClass should ===(classOf[ArteryControlMessageSerializer])
          }

          s"serialize and de-serialize $scenario" in {
            verifySerialization(item)
          }
      }

    "not support UniqueAddresses without host/port set" in pending

    "reject invalid manifest" in {
      intercept[IllegalArgumentException] {
        val serializer = new ArteryControlMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
        serializer.manifest("INVALID")
      }
    }

    "reject deserialization with invalid manifest" in {
      intercept[IllegalArgumentException] {
        val serializer = new ArteryControlMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
        serializer.fromBinary(Array.empty[Byte], "INVALID")
      }
    }

    def verifySerialization(msg: AnyRef): Unit = {
      val serializer = new ArteryControlMessageSerializer(system.asInstanceOf[ExtendedActorSystem])
      serializer.fromBinary(serializer.toBinary(msg), serializer.manifest(msg)) should ===(msg)
    }

    def uniqueAddress(): UniqueAddress =
      UniqueAddress(Address("abc", "def", "host", 12345), 2342)
  }
}


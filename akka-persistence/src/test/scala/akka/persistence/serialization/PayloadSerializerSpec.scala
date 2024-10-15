/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.serialization

import akka.persistence.FilteredPayload
import akka.persistence.SerializedEvent
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import akka.serialization.Serializers
import akka.testkit.AkkaSpec

class PayloadSerializerSpec extends AkkaSpec {

  private val serialization = SerializationExtension(system)

  "PayloadSerializer" should {
    "serialize FilteredPayload to zero-byte array" in {
      val serializer = serialization.findSerializerFor(FilteredPayload).asInstanceOf[SerializerWithStringManifest]
      val manifest = serializer.manifest(FilteredPayload)
      val serialized = serializer.toBinary(FilteredPayload)
      serialized should have(size(0))
      serializer.fromBinary(serialized, manifest) should be(FilteredPayload)
    }

    "serialize SerializedEvent" in {
      val event = "event1"
      val eventSerializer = serialization.findSerializerFor(event)
      val serializedEvent = new SerializedEvent(
        bytes = eventSerializer.toBinary(event),
        serializerId = eventSerializer.identifier,
        serializerManifest = Serializers.manifestFor(eventSerializer, event))

      val serializedEventSerializer = serialization.findSerializerFor(serializedEvent)
      val serializedEventBytes = serialization.serialize(serializedEvent).get

      val deserializied = serialization
        .deserialize(
          serializedEventBytes,
          serializedEventSerializer.identifier,
          Serializers.manifestFor(serializedEventSerializer, serializedEvent))
        .get
        .asInstanceOf[SerializedEvent]

      val deserializiedEvent =
        serialization.deserialize(deserializied.bytes, deserializied.serializerId, deserializied.serializerManifest).get
      deserializiedEvent shouldBe event
    }
  }

}

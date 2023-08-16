/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query.internal

import java.time.Instant
import java.util.UUID

import akka.persistence.query.NoOffset
import akka.persistence.query.Sequence
import akka.persistence.query.TimeBasedUUID
import akka.persistence.query.TimestampOffset
import akka.persistence.query.typed.EventEnvelope
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import akka.serialization.Serializers
import akka.testkit.AkkaSpec

class QuerySerializerSpec extends AkkaSpec {

  private val serialization = SerializationExtension(system)

  def verifySerialization(obj: AnyRef): Unit = {
    val serializer = serialization.findSerializerFor(obj).asInstanceOf[SerializerWithStringManifest]
    val manifest = serializer.manifest(obj)
    val bytes = serialization.serialize(obj).get
    val deserialized = serialization.deserialize(bytes, serializer.identifier, manifest).get
    deserialized shouldBe obj
  }

  "Query serializer" should {
    "serialize EventEnvelope with Sequence Offset" in {
      verifySerialization(
        EventEnvelope(
          Sequence(1L),
          "TestEntity|id1",
          3L,
          "event1",
          System.currentTimeMillis(),
          "TestEntity",
          5,
          filtered = false,
          source = ""))
    }

    "serialize EventEnvelope with Meta" in {
      verifySerialization(
        new EventEnvelope(
          Sequence(1L),
          "TestEntity|id1",
          3L,
          Some("event1"),
          System.currentTimeMillis(),
          Some("some-meta"),
          "TestEntity",
          5,
          filtered = false,
          source = ""))
    }

    "serialize EventEnvelope with filtered" in {
      verifySerialization(
        new EventEnvelope(
          Sequence(1L),
          "TestEntity|id1",
          3L,
          Some("event1"),
          System.currentTimeMillis(),
          Some("some-meta"),
          "TestEntity",
          5,
          filtered = true,
          source = ""))
    }

    "serialize EventEnvelope with source and tags" in {
      verifySerialization(
        new EventEnvelope(
          Sequence(1L),
          "TestEntity|id1",
          3L,
          Some("event1"),
          System.currentTimeMillis(),
          Some("some-meta"),
          "TestEntity",
          5,
          filtered = false,
          source = "query",
          tags = Set("tag1", "tag2")))
    }

    "serialize EventEnvelope with Timestamp Offset" in {
      verifySerialization(
        EventEnvelope(
          TimestampOffset(Instant.now(), Instant.now(), Map("pid1" -> 3)),
          "TestEntity|id1",
          3L,
          "event1",
          System.currentTimeMillis(),
          "TestEntity",
          5,
          filtered = false,
          source = ""))
    }

    "serialize EventEnvelope with TimeBasedUUID Offset" in {
      //2019-12-16T15:32:36.148Z[UTC]
      val uuidString = "49225740-2019-11ea-a752-ffae2393b6e4"
      val timeUuidOffset = TimeBasedUUID(UUID.fromString(uuidString))
      verifySerialization(
        EventEnvelope(
          timeUuidOffset,
          "TestEntity|id1",
          3L,
          "event1",
          System.currentTimeMillis(),
          "TestEntity",
          5,
          filtered = false,
          source = ""))
    }

    "serialize Sequence Offset" in {
      verifySerialization(Sequence(0))
    }

    "serialize Timestamp Offset" in {
      verifySerialization(TimestampOffset(Instant.now(), Instant.now(), Map("pid1" -> 3)))
      verifySerialization(TimestampOffset(Instant.now(), Instant.now(), Map("pid1" -> 3, "pid2" -> 4)))
      verifySerialization(TimestampOffset(Instant.now(), Instant.now(), Map.empty))
      verifySerialization(TimestampOffset(Instant.now(), Map.empty))
    }

    "serialize TimeBasedUUID Offset" in {
      //2019-12-16T15:32:36.148Z[UTC]
      val uuidString = "49225740-2019-11ea-a752-ffae2393b6e4"
      val timeUuidOffset = TimeBasedUUID(UUID.fromString(uuidString))
      verifySerialization(timeUuidOffset)
    }

    "serialize NoOffset" in {
      verifySerialization(NoOffset)
    }

    "support lazy deserialization of EventEnvelope" in {
      val event = "event1"
      val serializer = serialization.findSerializerFor(event)
      val eventBytes = serializer.toBinary(event)
      val eventSerializerId = serializer.identifier
      val eventManifest = Serializers.manifestFor(serializer, event)
      val env = EventEnvelope(
        TimestampOffset(Instant.now(), Instant.now(), Map("pid1" -> 3)),
        "TestEntity|id1",
        3L,
        eventBytes,
        bytes => Some(serialization.deserialize(bytes, eventSerializerId, eventManifest).get),
        System.currentTimeMillis(),
        None,
        "TestEntity",
        5,
        filtered = false,
        source = "",
        tags = Set.empty[String])

      env.eventBytes shouldBe Some(eventBytes)
      env.event shouldBe "event1"
    }
  }

}

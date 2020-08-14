/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.serialization

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.SerializationTestKit
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.crdt.Counter
import akka.persistence.typed.crdt.ORSet
import akka.persistence.typed.internal.PublishedEventImpl
import akka.persistence.typed.internal.ReplicatedPublishedEventMetaData
import akka.persistence.typed.internal.VersionVector
import org.scalatest.wordspec.AnyWordSpecLike

class ReplicatedEventSourcingSerializerSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  val testkit = new SerializationTestKit(system)

  "ReplicatedEventSourcingSerializer" should {
    "serializer" in {
      testkit.verifySerialization(ORSet.empty(ReplicaId("R1")))
      testkit.verifySerialization(ORSet.empty(ReplicaId("R1")).add("cat"))
      testkit.verifySerialization(ORSet.empty(ReplicaId("R1")).remove("cat"))
      testkit.verifySerialization(ORSet.empty(ReplicaId("R1")).addAll(Set("one", "two")))
      testkit.verifySerialization(ORSet.empty(ReplicaId("R1")).removeAll(Set("one", "two")))

      testkit.verifySerialization(Counter.empty)
      testkit.verifySerialization(Counter.Updated(BigInt(10)))
      testkit.verifySerialization(Counter.empty.applyOperation(Counter.Updated(BigInt(12))))

      testkit.verifySerialization(VersionVector.empty)
      testkit.verifySerialization(VersionVector.empty.updated("a", 10))

      testkit.verifySerialization(
        PublishedEventImpl(
          PersistenceId.ofUniqueId("cat"),
          10,
          "payload",
          1,
          Some(new ReplicatedPublishedEventMetaData(ReplicaId("R1"), VersionVector.empty))),
        assertEquality = false)

      testkit.verifySerialization(
        PublishedEventImpl(PersistenceId.ofUniqueId("cat"), 10, "payload", 1, None),
        assertEquality = false)
    }
  }

}

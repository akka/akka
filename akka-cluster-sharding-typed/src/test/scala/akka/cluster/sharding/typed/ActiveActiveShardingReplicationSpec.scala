/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.PublishedEvent
import akka.persistence.typed.internal.PublishedEventImpl
import org.scalatest.wordspec.AnyWordSpecLike

class ActiveActiveShardingReplicationSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "Active active sharding replication" must {

    "replicate published events to all sharding proxies" in {
      val replicaAProbe = createTestProbe[Any]()
      val replicaBProbe = createTestProbe[Any]()
      val replicaCProbe = createTestProbe[Any]()

      spawn(
        ActiveActiveShardingReplication(
          "ReplicaA",
          replicaShardingProxies =
            Map("ReplicaA" -> replicaAProbe.ref, "ReplicaB" -> replicaBProbe.ref, "ReplicaC" -> replicaCProbe.ref)))

      system.eventStream ! EventStream.Publish(
        PublishedEventImpl(
          Some("ReplicaA"),
          PersistenceId.replicatedUniqueId("pid", "ReplicaA"),
          1L,
          "event",
          System.currentTimeMillis()))

      replicaBProbe.expectMessageType[PublishedEvent]
      replicaCProbe.expectMessageType[PublishedEvent]
      replicaAProbe.expectNoMessage() // no publishing to the replica emitting it
    }

  }

}

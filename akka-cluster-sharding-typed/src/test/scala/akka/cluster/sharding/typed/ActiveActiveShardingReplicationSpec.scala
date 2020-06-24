/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.internal.PublishedEventImpl
import org.scalatest.wordspec.AnyWordSpecLike

class ActiveActiveShardingReplicationSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {

  "Active active sharding replication" must {

    "replicate published events to all sharding proxies" in {
      val replicaAProbe = createTestProbe[Any]()
      val replicaBProbe = createTestProbe[Any]()
      val replicaCProbe = createTestProbe[Any]()

      val replicationActor = spawn(
        ActiveActiveShardingReplication(
          "ReplicaA",
          replicaShardingProxies =
            Map("ReplicaA" -> replicaAProbe.ref, "ReplicaB" -> replicaBProbe.ref, "ReplicaC" -> replicaCProbe.ref)))

      val upProbe = createTestProbe[Done]()
      replicationActor ! ActiveActiveShardingReplication.VerifyStarted(upProbe.ref)
      upProbe.receiveMessage() // not bullet proof wrt to subscription being complete but good enough

      val event = PublishedEventImpl(
        Some("ReplicaA"),
        PersistenceId.replicatedUniqueId("pid", "ReplicaA"),
        1L,
        "event",
        System.currentTimeMillis())
      system.eventStream ! EventStream.Publish(event)

      replicaBProbe.expectMessageType[ShardingEnvelope[_]].message should equal(event)
      replicaCProbe.expectMessageType[ShardingEnvelope[_]].message should equal(event)
      replicaAProbe.expectNoMessage() // no publishing to the replica emitting it
    }

  }

}

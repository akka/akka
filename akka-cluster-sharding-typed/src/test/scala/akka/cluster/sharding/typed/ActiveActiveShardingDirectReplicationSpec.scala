/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import org.scalatest.wordspec.AnyWordSpecLike
import akka.Done
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.eventstream.EventStream
import akka.persistence.typed
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.PublishedEvent
import akka.persistence.typed.internal.{ PublishedEventImpl, ReplicatedPublishedEventMetaData, VersionVector }
import akka.persistence.typed.ReplicaId

class ActiveActiveShardingDirectReplicationSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with LogCapturing {

  "Active active sharding replication" must {

    "replicate published events to all sharding proxies" in {
      val replicaAProbe = createTestProbe[ShardingEnvelope[PublishedEvent]]()
      val replicaBProbe = createTestProbe[ShardingEnvelope[PublishedEvent]]()
      val replicaCProbe = createTestProbe[ShardingEnvelope[PublishedEvent]]()

      val replicationActor = spawn(
        ActiveActiveShardingDirectReplication(
          typed.ReplicaId("ReplicaA"),
          replicaShardingProxies = Map(
            ReplicaId("ReplicaA") -> replicaAProbe.ref,
            ReplicaId("ReplicaB") -> replicaBProbe.ref,
            ReplicaId("ReplicaC") -> replicaCProbe.ref)))

      val upProbe = createTestProbe[Done]()
      replicationActor ! ActiveActiveShardingDirectReplication.VerifyStarted(upProbe.ref)
      upProbe.receiveMessage() // not bullet proof wrt to subscription being complete but good enough

      val event = PublishedEventImpl(
        PersistenceId.replicatedUniqueId("pid", ReplicaId("ReplicaA")),
        1L,
        "event",
        System.currentTimeMillis(),
        Some(new ReplicatedPublishedEventMetaData(ReplicaId("ReplicaA"), VersionVector.empty)))
      system.eventStream ! EventStream.Publish(event)

      replicaBProbe.receiveMessage().message should equal(event)
      replicaCProbe.receiveMessage().message should equal(event)
      replicaAProbe.expectNoMessage() // no publishing to the replica emitting it
    }

  }

}

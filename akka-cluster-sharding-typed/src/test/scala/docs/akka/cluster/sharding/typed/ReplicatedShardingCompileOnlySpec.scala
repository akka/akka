/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.cluster.sharding.typed.ReplicatedEntity
import akka.cluster.sharding.typed.ReplicatedEntityProvider
import akka.cluster.sharding.typed.ReplicatedSharding
import akka.cluster.sharding.typed.ReplicatedShardingExtension
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityRef
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.ReplicationId
import com.github.ghik.silencer.silent

@silent("never used")
object ReplicatedShardingCompileOnlySpec {

  sealed trait Command

  val system: ActorSystem[_] = ???

  object MyEventSourcedBehavior {
    def apply(replicationId: ReplicationId): Behavior[Command] = ???
  }

  //#bootstrap
  ReplicatedEntityProvider[Command, ShardingEnvelope[Command]](
    "MyEntityType",
    Set(ReplicaId("DC-A"), ReplicaId("DC-B"))) { (entityTypeKey, replicaId) =>
    ReplicatedEntity(replicaId, Entity(entityTypeKey) { entityContext =>
      // the sharding entity id contains the business entityId, entityType, and replica id
      // which you'll need to create a ReplicatedEventSourcedBehavior
      val replicationId = ReplicationId.fromString(entityContext.entityId)
      MyEventSourcedBehavior(replicationId)
    })
  }
  //#bootstrap

  //#bootstrap-dc
  ReplicatedEntityProvider[Command, ShardingEnvelope[Command]](
    "MyEntityType",
    Set(ReplicaId("DC-A"), ReplicaId("DC-B"))) { (entityTypeKey, replicaId) =>
    ReplicatedEntity(replicaId, Entity(entityTypeKey) { entityContext =>
      val replicationId = ReplicationId.fromString(entityContext.entityId)
      MyEventSourcedBehavior(replicationId)
    }.withDataCenter(replicaId.id))
  }
  //#bootstrap-dc

  //#bootstrap-role
  val provider = ReplicatedEntityProvider[Command, ShardingEnvelope[Command]](
    "MyEntityType",
    Set(ReplicaId("DC-A"), ReplicaId("DC-B"))) { (entityTypeKey, replicaId) =>
    ReplicatedEntity(replicaId, Entity(entityTypeKey) { entityContext =>
      val replicationId = ReplicationId.fromString(entityContext.entityId)
      MyEventSourcedBehavior(replicationId)
    }.withRole(replicaId.id))
  }
  //#bootstrap-role

  //#sending-messages
  val myReplicatedSharding: ReplicatedSharding[Command, ShardingEnvelope[Command]] =
    ReplicatedShardingExtension(system).init(provider)

  val entityRefs: Map[ReplicaId, EntityRef[Command]] = myReplicatedSharding.entityRefsFor("myEntityId")
  val actorRefs: Map[ReplicaId, ActorRef[ShardingEnvelope[Command]]] = myReplicatedSharding.shardingRefs
  //#sending-messages
}

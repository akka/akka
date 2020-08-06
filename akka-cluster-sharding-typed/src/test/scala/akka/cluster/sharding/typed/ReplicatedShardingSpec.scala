/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import java.util.concurrent.ThreadLocalRandom

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.ReplicatedEventSourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

object ReplicatedShardingSpec {
  def config = ConfigFactory.parseString("""
      akka.loglevel = DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = "cluster"
      # pretend we're a node in all dc:s
      akka.cluster.roles = ["DC-A", "DC-B", "DC-C"]
      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0""").withFallback(PersistenceTestKitPlugin.config)
}

class ReplicatedShardingSpec
    extends ScalaTestWithActorTestKit(ReplicatedShardingSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  object MyReplicatedStringSet {
    trait Command extends CborSerializable
    case class Add(text: String) extends Command
    case class GetTexts(replyTo: ActorRef[Texts]) extends Command
    case class Texts(texts: Set[String]) extends CborSerializable

    def apply(entityId: String, replicaId: ReplicaId, allReplicas: Set[ReplicaId]): Behavior[Command] =
      ReplicatedEventSourcing.withSharedJournal(
        "StringSet",
        entityId,
        replicaId,
        allReplicas,
        PersistenceTestKitReadJournal.Identifier) { replicationContext =>
        EventSourcedBehavior[Command, String, Set[String]](
          replicationContext.persistenceId,
          Set.empty[String],
          (state, command) =>
            command match {
              case Add(text) =>
                Effect.persist(text)
              case GetTexts(replyTo) =>
                replyTo ! Texts(state)
                Effect.none
            },
          (state, event) => state + event).withJournalPluginId(PersistenceTestKitPlugin.PluginId)
      }
  }

  object ProxyActor {
    sealed trait Command
    case class ForwardToRandom(entityId: String, msg: MyReplicatedStringSet.Command) extends Command
    case class ForwardToAll(entityId: String, msg: MyReplicatedStringSet.Command) extends Command

    def apply(): Behavior[Command] = Behaviors.setup { context =>
      // #bootstrap
      val replicatedShardingProvider =
        ReplicatedEntityProvider[MyReplicatedStringSet.Command, ShardingEnvelope[MyReplicatedStringSet.Command]](
          // all replicas
          Set(ReplicaId("DC-A"), ReplicaId("DC-B"), ReplicaId("DC-C"))) { (entityTypeKey, replicaId, allReplicaIds) =>
          // factory for replicated entity for a given replica
          ReplicatedEntity(
            replicaId,
            // use the provided entity type key for sharding to get one sharding instance per replica
            Entity(entityTypeKey) { entityContext =>
              // factory for the entity for a given entity in that replica
              MyReplicatedStringSet(entityContext.entityId, replicaId, allReplicaIds)
            }
            // potentially use replica id as role or dc in Akka multi dc for the sharding instance
            // to control where replicas will live
            // .withDataCenter(replicaId.id))
              .withRole(replicaId.id))
        }

      val replicatedSharding = ReplicatedShardingExtension(context.system).init(replicatedShardingProvider)
      // #bootstrap

      Behaviors.receiveMessage {
        case ForwardToAll(entityId, cmd) =>
          // #all-entity-refs
          replicatedSharding.entityRefsFor(entityId).foreach {
            case (_, ref) => ref ! cmd
          }
          // #all-entity-refs
          Behaviors.same
        case ForwardToRandom(entityId, cmd) =>
          val refs = replicatedSharding.entityRefsFor(entityId)
          val chosenIdx = ThreadLocalRandom.current().nextInt(refs.size)
          refs.values.toIndexedSeq(chosenIdx) ! cmd;
          Behaviors.same
      }
    }
  }

  "Replicated sharding" should {

    "form a one node cluster" in {
      val node = Cluster(system)
      node.manager ! Join(node.selfMember.address)
      eventually {
        node.selfMember.status should ===(MemberStatus.Up)
      }
    }

    "forward to replicas" in {
      val proxy = spawn(ProxyActor())

      proxy ! ProxyActor.ForwardToAll("id1", MyReplicatedStringSet.Add("to-all"))
      proxy ! ProxyActor.ForwardToRandom("id1", MyReplicatedStringSet.Add("to-random"))

      eventually {
        val probe = createTestProbe[MyReplicatedStringSet.Texts]()
        proxy ! ProxyActor.ForwardToAll("id1", MyReplicatedStringSet.GetTexts(probe.ref))
        val responses: Seq[MyReplicatedStringSet.Texts] = probe.receiveMessages(3)
        val uniqueTexts = responses.flatMap(res => res.texts).toSet
        uniqueTexts should ===(Set("to-all", "to-random"))
      }

    }

  }

}

/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed.activeactive

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.ActiveActiveShardingExtension
import akka.cluster.sharding.typed.ActiveActiveShardingReplicaSettings
import akka.cluster.sharding.typed.ActiveActiveShardingSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.ActiveActiveEventSourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.immutable

object ActiveActiveShardingSpec {
  def config = ConfigFactory.parseString("""
      akka.loglevel = DEBUG
      akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
      akka.actor.provider = "cluster"
      # pretend we're a node in all dc:s
      akka.cluster.roles = ["DC-A", "DC-B", "DC-C"]
      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0""").withFallback(PersistenceTestKitPlugin.config)
}

class ActiveActiveShardingSpec
    extends ScalaTestWithActorTestKit(ActiveActiveShardingSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  object MyActiveActiveStringSet {
    trait Command extends CborSerializable
    case class Add(text: String) extends Command
    case class GetTexts(replyTo: ActorRef[Texts]) extends Command
    case class Texts(texts: Set[String]) extends CborSerializable

    def apply(entityId: String, replicaId: ReplicaId, allReplicas: immutable.Seq[ReplicaId]): Behavior[Command] =
      ActiveActiveEventSourcing.withSharedJournal(
        entityId,
        replicaId,
        allReplicas.toSet, // FIXME list in sharding settings vs set in aa (was thinking ordering is important for sharding)
        PersistenceTestKitReadJournal.Identifier) { aaContext =>
        EventSourcedBehavior[Command, String, Set[String]](
          aaContext.persistenceId,
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
    case class ForwardToRandom(entityId: String, msg: MyActiveActiveStringSet.Command) extends Command
    case class ForwardToAll(entityId: String, msg: MyActiveActiveStringSet.Command) extends Command

    def apply(): Behavior[Command] = Behaviors.setup { context =>
      val aaShardingSettings =
        ActiveActiveShardingSettings[
          MyActiveActiveStringSet.Command,
          ShardingEnvelope[MyActiveActiveStringSet.Command]](
          // all replicas
          List(ReplicaId("DC-A"), ReplicaId("DC-B"), ReplicaId("DC-C"))) { (entityTypeKey, replicaId, allReplicaIds) =>
          // factory for replica settings for a given replica
          ActiveActiveShardingReplicaSettings(
            replicaId,
            // use the replica id as typekey for sharding to get one sharding instance per replica
            Entity(entityTypeKey) { entityContext =>
              // factory for the entity for a given entity in that replica
              MyActiveActiveStringSet(entityContext.entityId, replicaId, allReplicaIds)
            }
            // potentially use replica id as role or dc in Akka multi dc for the sharding instance
            // to control where replicas will live
            // .withDataCenter(replicaId.id))
              .withRole(replicaId.id))
        }

      val aaSharding = ActiveActiveShardingExtension(context.system).init(aaShardingSettings)

      Behaviors.receiveMessage {
        case ForwardToAll(entityId, cmd) =>
          aaSharding.entityRefsFor(entityId).foreach {
            case (_, ref) => ref ! cmd
          }
          Behaviors.same
        case ForwardToRandom(entityId, cmd) =>
          aaSharding.randomRefFor(entityId) ! cmd
          Behaviors.same
      }
    }
  }

  "Active active sharding" should {

    "form a one node cluster" in {
      val node = Cluster(system)
      node.manager ! Join(node.selfMember.address)
      eventually {
        node.selfMember.status should ===(MemberStatus.Up)
      }
    }

    "forward to replicas" in {
      val proxy = spawn(ProxyActor())

      proxy ! ProxyActor.ForwardToAll("id1", MyActiveActiveStringSet.Add("to-all"))
      proxy ! ProxyActor.ForwardToRandom("id1", MyActiveActiveStringSet.Add("to-random"))

      eventually {
        val probe = createTestProbe[MyActiveActiveStringSet.Texts]()
        proxy ! ProxyActor.ForwardToAll("id1", MyActiveActiveStringSet.GetTexts(probe.ref))
        val responses: Seq[MyActiveActiveStringSet.Texts] = probe.receiveMessages(3)
        val uniqueTexts = responses.flatMap(res => res.texts).toSet
        uniqueTexts should ===(Set("to-all", "to-random"))
      }

    }

  }

}

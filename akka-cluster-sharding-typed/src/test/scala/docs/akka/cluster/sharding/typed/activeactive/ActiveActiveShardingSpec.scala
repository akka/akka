package docs.akka.cluster.sharding.typed.activeactive

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.ClusterSettings
import akka.cluster.sharding.typed.ActiveActiveShardingExtension
import akka.cluster.sharding.typed.ActiveActiveShardingReplicaSettings
import akka.cluster.sharding.typed.ActiveActiveShardingSettings
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.testkit.PersistenceTestKitPlugin
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.ActiveActiveEventSourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior

import scala.collection.immutable

class ActiveActiveShardingSpec extends ScalaTestWithActorTestKit(PersistenceTestKitPlugin.config) {

  object MyActiveActiveStringSet {
    trait Command
    case class Add(text: String) extends Command
    case class GetTexts(replyTo: ActorRef[Set[String]]) extends Command

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
                replyTo ! state
                Effect.none
            },
          (state, event) => state + event).withJournalPluginId(PersistenceTestKitPlugin.PluginId)
      }
  }

  // Compile only API exploration for now

  object BootStrap {
    sealed trait Command
    case class ForwardToRandom(msg: ShardingEnvelope[MyActiveActiveStringSet.Command]) extends Command
    case class ForwardToAll(msg: ShardingEnvelope[MyActiveActiveStringSet.Command]) extends Command

    def apply(): Behavior[Command] = Behaviors.setup { context =>
      val aaShardingSettings =
        ActiveActiveShardingSettings[
          MyActiveActiveStringSet.Command,
          ShardingEnvelope[MyActiveActiveStringSet.Command]](
          // all replicas
          List(ReplicaId("DC-A"), ReplicaId("DC-B"), ReplicaId("DC-B"))) { (entityTypeKey, replicaId, allReplicaIds) =>
          // factory for replica settings
          ActiveActiveShardingReplicaSettings(
            replicaId,
            // use the replica id as typekey for sharding to get one sharding instance per replica
            Entity(entityTypeKey)(entityContext =>
              MyActiveActiveStringSet(entityContext.entityId, replicaId, allReplicaIds))
            // potentially use replica id as role or dc in Akka multi dc for the sharding instance
            // to control where replicas will live
              .withRole(replicaId.id)
              .withDataCenter(replicaId.id))
        }

      val aaSharding = ActiveActiveShardingExtension(context.system).init(aaShardingSettings)

      Behaviors.receiveMessage {
        case ForwardToAll(cmd) =>
          // FIXME id conversion here, id without replica vs id with replica inside sharding
          aaSharding.entityRefsFor(cmd.entityId)
          Behaviors.same

        case ForwardToAll(cmd) =>
          ???

      }
    }
  }

}

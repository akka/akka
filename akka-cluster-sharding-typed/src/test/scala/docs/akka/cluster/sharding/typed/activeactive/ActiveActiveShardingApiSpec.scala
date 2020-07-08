package docs.akka.cluster.sharding.typed.activeactive

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.ActiveActiveSharding
import akka.cluster.sharding.typed.scaladsl.ActiveActiveShardingReplicaSettings
import akka.cluster.sharding.typed.scaladsl.ActiveActiveShardingSettings
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.testkit.query.scaladsl.PersistenceTestKitReadJournal
import akka.persistence.typed.ReplicaId
import akka.persistence.typed.scaladsl.ActiveActiveEventSourcing
import akka.persistence.typed.scaladsl.Effect
import akka.persistence.typed.scaladsl.EventSourcedBehavior

class ActiveActiveShardingApiSpec extends ScalaTestWithActorTestKit {

  object MyActiveActiveStringSet {
    trait Command
    case class Add(text: String) extends Command
    case class GetTexts(replyTo: ActorRef[Set[String]]) extends Command

    def apply(entityId: String, replicaId: ReplicaId, allReplicas: Set[ReplicaId]): Behavior[Command] =
      ActiveActiveEventSourcing.withSharedJournal(
        entityId,
        replicaId,
        allReplicas,
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
          (state, event) => state + event)
      }
  }

  // Compile only API exploration for now

  object Guardian {
    def apply(): Behavior[Nothing] = Behaviors.setup { context =>
      val aaShardingSettings =
        ActiveActiveShardingSettings[
          MyActiveActiveStringSet.Command,
          ShardingEnvelope[MyActiveActiveStringSet.Command]](
          // all replicas
          Set(ReplicaId("DC-A"), ReplicaId("DC-B"), ReplicaId("DC-B")),
          // how to route messages over them
          ActiveActiveShardingSettings.Random) { (entityId, replicaId, allReplicaIds) =>
          // factory for replica settings
          ActiveActiveShardingReplicaSettings(
            replicaId,
            // use the replica id as typekey for sharding to get one sharding instance per replica
            Entity(EntityTypeKey(replicaId.id))(entityContext =>
              MyActiveActiveStringSet(entityContext.entityId, replicaId, allReplicaIds))
            // (potentially use replica id as role/dc in Akka multi dc)
              .withRole(replicaId.id))
        }

      val aaSharding = ActiveActiveSharding(context.system).init(aaShardingSettings)

      val aaEntityRef = aaSharding.entityRefFor("someId")

      aaEntityRef ! MyActiveActiveStringSet.Add("text 1")
      // or would it be better if this special ref did not pretend to be an actor?
      // aaEntityRef.tailchopTell(MyActiveActiveStringSet.Add("text 1"))
      // aaEntityRef.randomTell(MyActiveActiveStringSet.Add("text 1"))
      // aaEntityRef.publishToAll(MyActiveActiveStringSet.Add("text 1"))

      Behaviors.empty
    }
  }

}

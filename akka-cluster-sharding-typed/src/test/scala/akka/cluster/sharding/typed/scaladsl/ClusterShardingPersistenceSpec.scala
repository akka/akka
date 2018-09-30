/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehavior }
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.{ WordSpec, WordSpecLike }

object ClusterShardingPersistenceSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster

      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on

      akka.coordinated-shutdown.terminate-actor-system = off

      akka.actor {
        serialize-messages = off
        allow-java-serialization = off
      }

      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """.stripMargin)

  sealed trait Command
  final case class Add(s: String) extends Command
  final case class Get(replyTo: ActorRef[String]) extends Command
  final case object StopPlz extends Command

  val typeKey = EntityTypeKey[Command]("test")

  def persistentActor(entityId: String): Behavior[Command] =
    PersistentBehavior[Command, String, String](
      persistenceId = typeKey.persistenceIdFrom(entityId),
      emptyState = "",
      commandHandler = (state, cmd) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! s"$entityId:$state"
          Effect.none
        case StopPlz ⇒ Effect.stop
      },
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

}

class ClusterShardingPersistenceSpec extends ScalaTestWithActorTestKit(ClusterShardingPersistenceSpec.config) with WordSpecLike {
  import ClusterShardingPersistenceSpec._

  val sharding = ClusterSharding(system)

  "Typed cluster sharding with persistent actor" must {

    Cluster(system).manager ! Join(Cluster(system).selfMember.address)

    "start persistent actor" in {
      ClusterSharding(system).start(ShardedEntity(
        entityId ⇒ persistentActor(entityId),
        typeKey,
        StopPlz
      ))

      val p = TestProbe[String]()

      val ref = ClusterSharding(system).entityRefFor(typeKey, "123")
      ref ! Add("a")
      ref ! Add("b")
      ref ! Add("c")
      ref ! Get(p.ref)
      p.expectMessage("123:a|b|c")
    }
  }
}

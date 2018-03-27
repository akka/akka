/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.TypedAkkaSpecWithShutdown
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehaviors }
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.ConfigFactory

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

  def persistentActor(entityId: String): Behavior[Command] =
    PersistentBehaviors.receive[Command, String, String](
      entityId,
      initialState = "",
      commandHandler = (_, state, cmd) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! s"$entityId:$state"
          Effect.none
        case StopPlz ⇒ Effect.stop
      },
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

  val typeKey = EntityTypeKey[Command]("test")

}

class ClusterShardingPersistenceSpec extends ActorTestKit
  with TypedAkkaSpecWithShutdown {
  import ClusterShardingPersistenceSpec._

  override def config = ClusterShardingPersistenceSpec.config

  val sharding = ClusterSharding(system)

  "Typed cluster sharding with persistent actor" must {

    Cluster(system).manager ! Join(Cluster(system).selfMember.address)

    "start persistent actor" in {
      ClusterSharding(system).spawn[Command](persistentActor, Props.empty, typeKey,
        ClusterShardingSettings(system), maxNumberOfShards = 100, handOffStopMessage = StopPlz)

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

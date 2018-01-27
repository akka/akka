/*
 * Copyright (C) 2017-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.cluster.sharding.typed

import akka.actor.typed.{ ActorRef, Behavior, Props, TypedAkkaSpecWithShutdown }
import akka.persistence.typed.scaladsl.PersistentBehaviors
import akka.testkit.typed.TestKit
import akka.testkit.typed.scaladsl.TestProbe
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

  import PersistentBehaviors._

  val persistentActor: Behavior[Command] =
    PersistentBehaviors.persistentEntity[Command, String, String](
      persistenceIdFromActorName = name ⇒ "Test-" + name,
      initialState = "",
      commandHandler = (_, state, cmd) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! state
          Effect.none
        case StopPlz ⇒ Effect.stop
      },
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

  val typeKey = EntityTypeKey[Command]("test")

}

class ClusterShardingPersistenceSpec extends TestKit("ClusterShardingPersistenceSPec", ClusterShardingPersistenceSpec.config)
  with TypedAkkaSpecWithShutdown {
  import ClusterShardingPersistenceSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val s = system
  val sharding = ClusterSharding(system)

  implicit val untypedSystem = system.toUntyped
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

  "Typed cluster sharding with persistent actor" must {

    untypedCluster.join(untypedCluster.selfAddress)

    "start persistent actor" in {
      ClusterSharding(system).spawn(persistentActor, Props.empty, typeKey,
        ClusterShardingSettings(system), maxNumberOfShards = 100, handOffStopMessage = StopPlz)

      val p = TestProbe[String]()

      val ref = ClusterSharding(system).entityRefFor(typeKey, "123")
      ref ! Add("a")
      ref ! Add("b")
      ref ! Add("c")
      ref ! Get(p.ref)
      p.expectMsg("a|b|c")
    }
  }
}

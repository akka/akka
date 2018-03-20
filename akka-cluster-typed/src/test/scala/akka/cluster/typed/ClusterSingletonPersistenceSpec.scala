/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.typed.{ ActorRef, Behavior, Props, TypedAkkaSpecWithShutdown }
import akka.persistence.typed.scaladsl.{ Effect, PersistentBehaviors }
import akka.testkit.typed.scaladsl.{ ActorTestKit, TestProbe }
import com.typesafe.config.ConfigFactory

object ClusterSingletonPersistenceSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster

      akka.remote.artery.enabled = true
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

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
  private final case object StopPlz extends Command

  val persistentActor: Behavior[Command] =
    PersistentBehaviors.receive[Command, String, String](
      persistenceId = "TheSingleton",
      initialState = "",
      commandHandler = (_, state, cmd) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! state
          Effect.none
        case StopPlz ⇒ Effect.stop
      },
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

}

class ClusterSingletonPersistenceSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {
  import ClusterSingletonPersistenceSpec._
  import akka.actor.typed.scaladsl.adapter._

  override def config = ClusterSingletonPersistenceSpec.config

  implicit val s = system

  implicit val untypedSystem = system.toUntyped
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

  "A typed cluster singleton with persistent actor" must {

    untypedCluster.join(untypedCluster.selfAddress)

    "start persistent actor" in {
      val ref = ClusterSingleton(system).spawn(
        behavior = persistentActor,
        singletonName = "singleton",
        props = Props.empty,
        settings = ClusterSingletonSettings(system),
        terminationMessage = StopPlz)

      val p = TestProbe[String]()

      ref ! Add("a")
      ref ! Add("b")
      ref ! Add("c")
      ref ! Get(p.ref)
      p.expectMessage("a|b|c")
    }
  }
}

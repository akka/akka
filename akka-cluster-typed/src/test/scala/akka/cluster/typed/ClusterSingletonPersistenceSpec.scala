/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.typed

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior }
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.persistence.typed.PersistenceId
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object ClusterSingletonPersistenceSpec {
  val config = ConfigFactory.parseString(
    """
      akka.actor.provider = cluster
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
    EventSourcedBehavior[Command, String, String](
      persistenceId = PersistenceId("TheSingleton"),
      emptyState = "",
      commandHandler = (state, cmd) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! state
          Effect.none
        case StopPlz ⇒ Effect.stop()
      },
      eventHandler = (state, evt) ⇒ if (state.isEmpty) evt else state + "|" + evt)

}

class ClusterSingletonPersistenceSpec extends ScalaTestWithActorTestKit(ClusterSingletonPersistenceSpec.config) with WordSpecLike {
  import ClusterSingletonPersistenceSpec._
  import akka.actor.typed.scaladsl.adapter._

  implicit val s = system

  implicit val untypedSystem = system.toUntyped
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

  "A typed cluster singleton with persistent actor" must {

    untypedCluster.join(untypedCluster.selfAddress)

    "start persistent actor" in {
      val ref = ClusterSingleton(system).init(SingletonActor(persistentActor, "singleton").withStopMessage(StopPlz))

      val p = TestProbe[String]()

      ref ! Add("a")
      ref ! Add("b")
      ref ! Add("c")
      ref ! Get(p.ref)
      p.expectMessage("a|b|c")
    }
  }
}

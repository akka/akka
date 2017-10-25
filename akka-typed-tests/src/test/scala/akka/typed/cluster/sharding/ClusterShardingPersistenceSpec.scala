/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.typed.cluster.sharding

import akka.cluster.sharding.ShardCoordinator.LeastShardAllocationStrategy
import akka.typed.{ ActorRef, ActorSystem, Props, TypedSpec }
import akka.typed.cluster.Cluster
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.adapter._
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import akka.typed.Behavior
import akka.typed.persistence.scaladsl.PersistentActor
import akka.typed.persistence.scaladsl.PersistentActor.PersistNothing

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

  import PersistentActor._

  val persistentActor: Behavior[Command] =
    PersistentActor.persistentEntity[Command, String, String](
      persistenceIdFromActorName = name ⇒ "Test-" + name,
      initialState = "",
      commandHandler = CommandHandler((ctx, cmd, state) ⇒ cmd match {
        case Add(s) ⇒ Effect.persist(s)
        case Get(replyTo) ⇒
          replyTo ! state
          Effect.done
        case StopPlz ⇒ Effect.stop
      }),
      eventHandler = (evt, state) ⇒ if (state.isEmpty) evt else state + "|" + evt)

  val typeKey = EntityTypeKey[Command]("test")

}

class ClusterShardingPersistenceSpec extends TypedSpec(ClusterShardingPersistenceSpec.config) with ScalaFutures {
  import akka.typed.scaladsl.adapter._
  import ClusterShardingPersistenceSpec._

  implicit val s = system
  implicit val testkitSettings = TestKitSettings(system)
  val sharding = ClusterSharding(system)

  implicit val untypedSystem = system.toUntyped
  private val untypedCluster = akka.cluster.Cluster(untypedSystem)

  object `Typed cluster sharding with persistent actor` {

    untypedCluster.join(untypedCluster.selfAddress)

    def `01 start persistent actor`(): Unit = {
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

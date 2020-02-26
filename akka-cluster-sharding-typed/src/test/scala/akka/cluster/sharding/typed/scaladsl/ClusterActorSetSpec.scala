/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.typed.ActorRef
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.EntityId
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object ClusterActorSetSpec {
  // single node cluster config
  def config = ConfigFactory.parseString("""
      akka.actor.provider = cluster

      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on

      akka.cluster.sharding.number-of-shards = 10

      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      """)

  object MyActor {
    trait Command
    case object Stop extends Command

    case class Started(entityId: EntityId, selfRef: ActorRef[Command])

    def apply(entityId: EntityId, probe: ActorRef[Any]): Behavior[Command] = Behaviors.setup { ctx =>
      probe ! Started(entityId, ctx.self)

      Behaviors.receiveMessage {
        case Stop =>
          Behaviors.stopped
      }
    }

  }

}

class ClusterActorSetSpec
    extends ScalaTestWithActorTestKit(ClusterActorSetSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  import ClusterActorSetSpec._

  "The ClusterActorSet" must {

    "have a single node cluster running first" in {
      val probe = createTestProbe()
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      probe.awaitAssert({
        Cluster(system).selfMember.status == MemberStatus.Up
      }, 3.seconds)
    }

    "start N actors with unique ids" in {
      val probe = createTestProbe[Any]()
      val settings = ClusterActorSetSettings(system).withKeepAliveInterval(1.second) // ping/start fast
      ClusterActorSet(system).init(settings, numberOfEntities = 5, entityId => MyActor(entityId, probe.ref))

      val started = probe.receiveMessages(5)
      started.toSet.size should ===(5)
    }

    "start actors with specific ids" in {
      val probe = createTestProbe[Any]()

      val settings = ClusterActorSetSettings(system).withKeepAliveInterval(1.second) // ping/start fast
      val identities = Set("a", "b", "c")
      ClusterActorSet(system).init(settings, identities, entityId => MyActor(entityId, probe.ref))

      val started = (1 to 3).map(_ => probe.expectMessageType[MyActor.Started]).toSet
      started.map(_.entityId) should ===(identities)
    }

    "restart actors if they stop" in {
      val probe = createTestProbe[Any]()

      val settings = ClusterActorSetSettings(system).withKeepAliveInterval(1.second) // ping/start fast
      ClusterActorSet(system).init(settings, 2, entityId => MyActor(entityId, probe.ref))

      val started = (1 to 2).map(_ => probe.expectMessageType[MyActor.Started]).toSet
      started.foreach(_.selfRef ! MyActor.Stop)

      (1 to 2).map(_ => probe.expectMessageType[MyActor.Started])
    }

  }

}

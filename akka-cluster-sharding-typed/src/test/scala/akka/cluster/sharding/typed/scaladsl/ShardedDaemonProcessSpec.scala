/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object ShardedDaemonProcessSpec {
  // single node cluster config
  def config = ConfigFactory.parseString("""
      akka.actor.provider = cluster

      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on
      
      # ping often/start fast for test
      akka.cluster.sharded-daemon-process.keep-alive-interval = 1s

      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      """)

  object MyActor {
    trait Command
    case object Stop extends Command

    case class Started(id: Int, selfRef: ActorRef[Command])

    def apply(id: Int, probe: ActorRef[Any]): Behavior[Command] = Behaviors.setup { ctx =>
      probe ! Started(id, ctx.self)

      Behaviors.receiveMessage {
        case Stop =>
          Behaviors.stopped
      }
    }

  }

}

class ShardedDaemonProcessSpec
    extends ScalaTestWithActorTestKit(ShardedDaemonProcessSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  import ShardedDaemonProcessSpec._

  "The ShardedDaemonSet" must {

    "have a single node cluster running first" in {
      val probe = createTestProbe()
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      probe.awaitAssert({
        Cluster(system).selfMember.status == MemberStatus.Up
      }, 3.seconds)
    }

    "start N actors with unique ids" in {
      val probe = createTestProbe[Any]()
      ShardedDaemonProcess(system).init("a", 5, id => MyActor(id, probe.ref))

      val started = probe.receiveMessages(5)
      started.toSet.size should ===(5)
    }

    "start actors with specific ids" in {
      val probe = createTestProbe[Any]()
      ShardedDaemonProcess(system).init("b", 3, id => MyActor(id, probe.ref))

      val started = (1 to 3).map(_ => probe.expectMessageType[MyActor.Started]).toSet
      started.map(_.id) should ===((0 to 2).toSet)
    }

    "restart actors if they stop" in {
      val probe = createTestProbe[Any]()
      ShardedDaemonProcess(system).init("c", 2, id => MyActor(id, probe.ref))

      val started = (1 to 2).map(_ => probe.expectMessageType[MyActor.Started]).toSet
      started.foreach(_.selfRef ! MyActor.Stop)

      // periodic ping every 1s makes it restart
      (1 to 2).map(_ => probe.expectMessageType[MyActor.Started](3.seconds))
    }

  }

}

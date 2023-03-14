/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.MemberStatus
import akka.cluster.sharding.typed.ClusterShardingSettings
import akka.cluster.sharding.typed.ShardedDaemonProcessSettings
import akka.cluster.sharding.typed.internal.ShardedDaemonProcessCoordinator
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import com.typesafe.config.ConfigFactory
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

object ShardedDaemonProcessSpec {
  // single node cluster config
  def config = ConfigFactory.parseString("""
      akka.actor.provider = cluster

      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.cluster.jmx.multi-mbeans-in-same-jvm = on
      
      # ping often/start fast for test
      akka.cluster.sharded-daemon-process.keep-alive-interval = 1s
      akka.cluster.sharded-daemon-process.keep-alive-throttle-interval = 20ms

      akka.coordinated-shutdown.terminate-actor-system = off
      akka.coordinated-shutdown.run-by-actor-system-terminate = off
      """)

  object MyActor {
    sealed trait Command
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

  "The ShardedDaemonProcess" must {

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
      probe.expectNoMessage()
    }

    "restart actors if they stop" in {
      val probe = createTestProbe[Any]()
      ShardedDaemonProcess(system).init("stop", 2, id => MyActor(id, probe.ref))

      val started = (1 to 2).map(_ => probe.expectMessageType[MyActor.Started]).toSet
      started.foreach(_.selfRef ! MyActor.Stop)

      // periodic ping every 1s makes it restart
      (1 to 2).map(_ => probe.expectMessageType[MyActor.Started](3.seconds))
    }

    "not run if the role does not match node role" in {
      val probe = createTestProbe[Any]()
      val settings = ShardedDaemonProcessSettings(system).withRole("workers")
      ShardedDaemonProcess(system).init("roles", 3, id => MyActor(id, probe.ref), settings, None)

      probe.expectNoMessage()
    }

  }

  "KeepAlivePinger" must {
    "have a single node cluster running first" in {
      val probe = createTestProbe()
      Cluster(system).manager ! Join(Cluster(system).selfMember.address)
      probe.awaitAssert({
        Cluster(system).selfMember.status == MemberStatus.Up
      }, 3.seconds)
    }

    "send keep alive messages (rescalable)" in {
      val shardingProbe = createTestProbe[Any]()
      val settings = ShardedDaemonProcessSettings(system)
      val shardingSettings = ClusterShardingSettings(system)
      val pinger =
        spawn(ShardedDaemonProcessCoordinator(settings, shardingSettings, true, 3, "throttle-a", shardingProbe.ref))
      // note that StartEntity.apply is actually a ShardingEnvelope wrapping the StartEntity message
      // See ShardedDaemonProcessImpl.DecodedId for details about entity id format
      shardingProbe.expectMessage(StartEntity("0|3|0"))
      shardingProbe.expectMessage(StartEntity("0|3|1"))
      shardingProbe.expectMessage(StartEntity("0|3|2"))
      shardingProbe.expectMessage(StartEntity("0|3|0"))

      testKit.stop(pinger)
    }

    "throttle keep alive messages (non-rescalable)" in {
      val shardingProbe = createTestProbe[Any]()
      val settings = ShardedDaemonProcessSettings(system).withKeepAliveThrottleInterval(1.second)
      val shardingSettings = ClusterShardingSettings(system)
      val pinger =
        spawn(ShardedDaemonProcessCoordinator(settings, shardingSettings, true, 2, "throttle-b", shardingProbe.ref))
      // note that StartEntity.apply is actually a ShardingEnvelope wrapping the StartEntity message
      // See ShardedDaemonProcessImpl.DecodedId for details about entity id format
      shardingProbe.expectMessage(StartEntity("0"))
      shardingProbe.expectNoMessage(100.millis)
      shardingProbe.expectMessage(StartEntity("1"))
      shardingProbe.expectNoMessage(100.millis)
      shardingProbe.expectMessage(StartEntity("2"))
      shardingProbe.expectNoMessage(100.millis)
      shardingProbe.expectMessage(StartEntity("0"))

      testKit.stop(pinger)
    }

  }

  object TagProcessor {
    sealed trait Command
    def apply(tag: String): Behavior[Command] = Behaviors.setup { ctx =>
      // start the processing ...
      ctx.log.debug("Starting processor for tag {}", tag)
      Behaviors.empty
    }
  }

  def docExample(): Unit = {
    // #tag-processing
    val tags = Vector("tag-1", "tag-2", "tag-3")
    ShardedDaemonProcess(system).init("TagProcessors", tags.size, id => TagProcessor(tags(id)))
    // #tag-processing
  }

}

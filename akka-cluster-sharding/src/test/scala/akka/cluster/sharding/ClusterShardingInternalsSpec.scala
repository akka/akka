/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ Actor, ExtendedActorSystem, NoSerializationVerificationNeeded, PoisonPill, Props }
import akka.cluster.sharding.ShardCoordinator.Internal.ShardStopped
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion.HandOffStopper
import akka.testkit.{ AkkaSpec, TestProbe }
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar

import scala.concurrent.duration._

object ClusterShardingInternalsSpec {
  case class HandOffStopMessage() extends NoSerializationVerificationNeeded
  class EmptyHandlerActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }

    override def postStop(): Unit = {
      super.postStop()
    }
  }
}

class ClusterShardingInternalsSpec extends AkkaSpec("""
    |akka.actor.provider = cluster
    |akka.remote.netty.tcp.port = 0
    |akka.remote.artery.canonical.port = 0
    |""".stripMargin) with MockitoSugar {
  import ClusterShardingInternalsSpec._

  val clusterSharding = spy(new ClusterSharding(system.asInstanceOf[ExtendedActorSystem]))

  "ClusterSharding" must {
    "start a region in proxy mode in case of node role mismatch" in {

      val settingsWithRole = ClusterShardingSettings(system).withRole("nonExistingRole")
      val typeName = "typeName"
      val extractEntityId = mock[ShardRegion.ExtractEntityId]
      val extractShardId = mock[ShardRegion.ExtractShardId]

      clusterSharding.start(
        typeName = typeName,
        entityProps = Props.empty,
        settings = settingsWithRole,
        extractEntityId = extractEntityId,
        extractShardId = extractShardId,
        allocationStrategy = mock[ShardAllocationStrategy],
        handOffStopMessage = PoisonPill)

      verify(clusterSharding).startProxy(
        ArgumentMatchers.eq(typeName),
        ArgumentMatchers.eq(settingsWithRole.role),
        ArgumentMatchers.eq(None),
        ArgumentMatchers.eq(extractEntityId),
        ArgumentMatchers.eq(extractShardId))
    }

    "HandOffStopper must stop the entity even if the entity doesn't handle handOffStopMessage" in {
      val probe = TestProbe()
      val shardName = "test"
      val emptyHandlerActor = system.actorOf(Props(new EmptyHandlerActor))
      val handOffStopper = system.actorOf(
        Props(new HandOffStopper(shardName, probe.ref, Set(emptyHandlerActor), HandOffStopMessage, 10.millis)))

      watch(emptyHandlerActor)
      expectTerminated(emptyHandlerActor, 1.seconds)

      probe.expectMsg(1.seconds, ShardStopped(shardName))

      watch(handOffStopper)
      expectTerminated(handOffStopper, 1.seconds)
    }
  }
}

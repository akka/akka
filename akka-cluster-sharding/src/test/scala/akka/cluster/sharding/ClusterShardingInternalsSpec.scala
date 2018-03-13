/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.{ ExtendedActorSystem, PoisonPill, Props }
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.testkit.AkkaSpec
import org.mockito.ArgumentMatchers
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar

class ClusterShardingInternalsSpec extends AkkaSpec("""akka.actor.provider = "cluster"""") with MockitoSugar {

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
  }
}

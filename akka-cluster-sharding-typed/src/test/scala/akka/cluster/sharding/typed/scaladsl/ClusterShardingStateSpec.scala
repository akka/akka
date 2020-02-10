/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.{ ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.ActorRef
import akka.cluster.sharding.ShardRegion.{ CurrentShardRegionState, ShardState }
import akka.cluster.sharding.typed.scaladsl.ClusterShardingSpec._
import akka.cluster.sharding.typed.{ GetShardRegionState, ShardingMessageExtractor }
import akka.cluster.typed.{ Cluster, Join }
import org.scalatest.wordspec.AnyWordSpecLike

class ClusterShardingStateSpec
    extends ScalaTestWithActorTestKit(ClusterShardingSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  val sharding = ClusterSharding(system)

  val shardExtractor = ShardingMessageExtractor.noEnvelope[IdTestProtocol](10, IdStopPlz()) {
    case IdReplyPlz(id, _)  => id
    case IdWhoAreYou(id, _) => id
    case other              => throw new IllegalArgumentException(s"Unexpected message $other")
  }

  val cluster = Cluster(system)

  val typeKey: EntityTypeKey[IdTestProtocol] = ClusterShardingSpec.typeKeyWithoutEnvelopes

  "Cluster Sharding" must {
    "allow querying of the shard region state" in {
      val probe = TestProbe[CurrentShardRegionState]()
      cluster.manager ! Join(cluster.selfMember.address)

      // Before the region is started
      sharding.shardState ! GetShardRegionState(typeKey, probe.ref)
      probe.expectMessage(CurrentShardRegionState(Set()))

      val shardingRef: ActorRef[IdTestProtocol] = sharding.init(
        Entity(typeKey)(_ => ClusterShardingSpec.behaviorWithId())
          .withStopMessage(IdStopPlz())
          .withMessageExtractor(idTestProtocolMessageExtractor))

      sharding.shardState ! GetShardRegionState(typeKey, probe.ref)
      probe.expectMessage(CurrentShardRegionState(Set()))

      // Create a shard
      val replyProbe = TestProbe[String]()
      shardingRef ! IdReplyPlz("id1", replyProbe.ref)
      replyProbe.expectMessage("Hello!")

      //#get-region-state
      ClusterSharding(system).shardState ! GetShardRegionState(typeKey, probe.ref)
      val state = probe.receiveMessage()
      //#get-region-state
      state.shards should be(Set(ShardState(shardExtractor.shardId("id1"), Set("id1"))))
    }
  }

}

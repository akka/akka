/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.actor.testkit.typed.scaladsl.{ LogCapturing, ScalaTestWithActorTestKit, TestProbe }
import akka.actor.typed.ActorRef
import akka.cluster.sharding.ShardRegion.{ ClusterShardingStats, ShardRegionStats }
import akka.cluster.sharding.typed.scaladsl.ClusterShardingSpec._
import akka.cluster.sharding.typed.{ ClusterShardingSettings, GetClusterShardingStats }
import akka.cluster.typed.{ Cluster, Join, SelfUp }
import org.scalatest.wordspec.AnyWordSpecLike

class ClusterShardingStatsSpec
    extends ScalaTestWithActorTestKit(ClusterShardingSpec.config)
    with AnyWordSpecLike
    with LogCapturing {

  val sharding = ClusterSharding(system)

  val typeKey: EntityTypeKey[IdTestProtocol] = ClusterShardingSpec.typeKeyWithoutEnvelopes

  val shardExtractor = ClusterShardingSpec.idTestProtocolMessageExtractor

  // no need to scale this up here for the cluster query versus one region
  val queryTimeout = ClusterShardingSettings(system).shardRegionQueryTimeout

  "Cluster Sharding" must {
    "return empty statistics if there are no running sharded entities" in {
      val cluster = Cluster(system)
      val upProbe = TestProbe[SelfUp]()

      cluster.subscriptions ! akka.cluster.typed.Subscribe(upProbe.ref, classOf[SelfUp])
      cluster.manager ! Join(cluster.selfMember.address)
      upProbe.expectMessageType[SelfUp]

      val emptyProbe = TestProbe[ClusterShardingStats]()
      sharding.shardState ! GetClusterShardingStats(queryTimeout, emptyProbe.ref)
      emptyProbe.expectMessage(ClusterShardingStats(Map.empty))
    }

    "allow querying of statistics of the currently running sharded entities in the entire cluster" in {

      val shardingRef: ActorRef[IdTestProtocol] = sharding.init(
        Entity(typeKey)(_ => ClusterShardingSpec.behaviorWithId())
          .withStopMessage(IdStopPlz())
          .withMessageExtractor(idTestProtocolMessageExtractor))

      val replyProbe = TestProbe[String]()
      val id1 = "id1"
      shardingRef ! IdReplyPlz(id1, replyProbe.ref)
      replyProbe.expectMessage("Hello!")

      val replyToProbe = TestProbe[ClusterShardingStats]()
      val replyTo = replyToProbe.ref

      //#get-cluster-sharding-stats
      ClusterSharding(system).shardState ! GetClusterShardingStats(queryTimeout, replyTo)
      val stats = replyToProbe.expectMessageType[ClusterShardingStats]
      //#get-cluster-sharding-stats

      val expect = ClusterShardingStats(
        Map(Cluster(system).selfMember.address -> ShardRegionStats(Map(shardExtractor.shardId(id1) -> 1), Set.empty)))

      stats shouldEqual expect
    }
  }

}

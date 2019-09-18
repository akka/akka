/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.MemberStatus
import akka.testkit._
import com.typesafe.config.ConfigFactory

abstract class MultiNodeClusterShardingSpecConfig(mode: String, rememberEntities: Boolean)
    extends MultiNodeClusterShardingConfig(mode, rememberEntities) {

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  nodeConfig(third)(ConfigFactory.parseString(s"""
    akka.cluster.sharding.distributed-data.durable.lmdb.dir = $targetDir/sharding-third
    """))

}

object PersistentClusterShardingRememberEntitiesEnabledConfig
    extends MultiNodeClusterShardingSpecConfig(ClusterShardingSettings.StateStoreModePersistence, true)

class PersistentClusterShardingMultiNodeSpecMultiJvmNode1
    extends MultiNodeClusterShardingSpecSpec(PersistentClusterShardingRememberEntitiesEnabledConfig)
class PersistentClusterShardingMultiNodeSpecMultiJvmNode2
    extends MultiNodeClusterShardingSpecSpec(PersistentClusterShardingRememberEntitiesEnabledConfig)
class PersistentClusterShardingMultiNodeSpecMultiJvmNode3
    extends MultiNodeClusterShardingSpecSpec(PersistentClusterShardingRememberEntitiesEnabledConfig)
class PersistentClusterShardingMultiNodeSpecMultiJvmNode4
    extends MultiNodeClusterShardingSpecSpec(PersistentClusterShardingRememberEntitiesEnabledConfig)

object DDataClusterShardingRememberEntitiesEnabledConfig
    extends MultiNodeClusterShardingSpecConfig(ClusterShardingSettings.StateStoreModeDData, true)

class DDataClusterShardingMultiNodeSpecMultiJvmNode1
    extends MultiNodeClusterShardingSpecSpec(DDataClusterShardingRememberEntitiesEnabledConfig)
class DDataClusterShardingMultiNodeSpecMultiJvmNode2
    extends MultiNodeClusterShardingSpecSpec(DDataClusterShardingRememberEntitiesEnabledConfig)
class DDataClusterShardingMultiNodeSpecMultiJvmNode3
    extends MultiNodeClusterShardingSpecSpec(DDataClusterShardingRememberEntitiesEnabledConfig)
class DDataClusterShardingMultiNodeSpecMultiJvmNode4
    extends MultiNodeClusterShardingSpecSpec(DDataClusterShardingRememberEntitiesEnabledConfig)

abstract class MultiNodeClusterShardingSpecSpec(multiNodeConfig: MultiNodeClusterShardingSpecConfig)
    extends MultiNodeClusterShardingSpec(multiNodeConfig) {
  import MultiNodeClusterShardingSpec._, multiNodeConfig._

  val dataType = "Entity"

  def startSharding(sys: ActorSystem, probe: ActorRef): ActorRef =
    super.startSharding(sys, MultiNodeClusterShardingSpec.props(probe), dataType)

  def expectEntityRestarted(sys: ActorSystem, event: Int, probe: TestProbe, entityProbe: TestProbe): EntityStarted = {
    if (!rememberEntities) {
      probe.send(ClusterSharding(sys).shardRegion(dataType), event)
      probe.expectMsg(1)
    }

    entityProbe.expectMsgType[EntityStarted](30.seconds)
  }

  s"Cluster sharding [rememberEntities=$rememberEntities, mode=$mode]" must {

    "restart and rebalance entities on coordinator node down" in within(30.seconds) {
      startPersistenceIfNotDdataMode(startOn = first, setStoreOn = Seq(first, second, third))

      val entityProbe = TestProbe()
      val probe = TestProbe()
      join(second, second)
      runOn(second) {
        startSharding(system, entityProbe.ref)
        probe.send(ClusterSharding(system).shardRegion(dataType), 1)
        probe.expectMsg(1)
        entityProbe.expectMsgType[EntityStarted]
      }
      enterBarrier("second-started")

      join(third, second)
      runOn(third) {
        startSharding(system, entityProbe.ref)
      }

      join(fourth, second)
      runOn(fourth) {
        startSharding(system, entityProbe.ref)
      }

      runOn(second, third, fourth) {
        within(remaining) {
          awaitAssert {
            cluster.state.members.size shouldEqual 3
            cluster.state.members.forall(_.status == MemberStatus.Up) shouldEqual true
          }
        }
      }
      enterBarrier("all-up")

      runOn(first) {
        if (isDdataMode) {
          // Entity 1 in region of first node was started when there was only one node
          // and then the remembering state will be replicated to second node by the
          // gossip. So we must give that a chance to replicate before shutting down second.
          Thread.sleep(5000)
        }
        testConductor.exit(second, 0).await
      }
      enterBarrier("bring-down-second")

      runOn(third) {
        expectEntityRestarted(system, 1, probe, entityProbe)
      }
      enterBarrier("done")
    }

  }

}

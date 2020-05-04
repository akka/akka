/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.sharding.ShardRegion.GracefulShutdown
import akka.remote.testconductor.RoleName
import akka.testkit._

abstract class ClusterShardingGracefulShutdownSpecConfig(mode: String)
    extends MultiNodeClusterShardingConfig(
      mode,
      additionalConfig = "akka.persistence.journal.leveldb-shared.store.native = off") {
  val first = role("first")
  val second = role("second")
}

object PersistentClusterShardingGracefulShutdownSpecConfig
    extends ClusterShardingGracefulShutdownSpecConfig(ClusterShardingSettings.StateStoreModePersistence)
object DDataClusterShardingGracefulShutdownSpecConfig
    extends ClusterShardingGracefulShutdownSpecConfig(ClusterShardingSettings.StateStoreModeDData)

class PersistentClusterShardingGracefulShutdownSpec
    extends ClusterShardingGracefulShutdownSpec(PersistentClusterShardingGracefulShutdownSpecConfig)
class DDataClusterShardingGracefulShutdownSpec
    extends ClusterShardingGracefulShutdownSpec(DDataClusterShardingGracefulShutdownSpecConfig)

class PersistentClusterShardingGracefulShutdownMultiJvmNode1 extends PersistentClusterShardingGracefulShutdownSpec
class PersistentClusterShardingGracefulShutdownMultiJvmNode2 extends PersistentClusterShardingGracefulShutdownSpec

class DDataClusterShardingGracefulShutdownMultiJvmNode1 extends DDataClusterShardingGracefulShutdownSpec
class DDataClusterShardingGracefulShutdownMultiJvmNode2 extends DDataClusterShardingGracefulShutdownSpec

abstract class ClusterShardingGracefulShutdownSpec(multiNodeConfig: ClusterShardingGracefulShutdownSpecConfig)
    extends MultiNodeClusterShardingSpec(multiNodeConfig)
    with ImplicitSender {

  import MultiNodeClusterShardingSpec.ShardedEntity
  import multiNodeConfig._

  private val typeName = "Entity"

  def join(from: RoleName, to: RoleName, typeName: String): Unit = {
    super.join(from, to)
    runOn(from) {
      startSharding(typeName)
    }
    enterBarrier(s"$from-started")
  }

  def startSharding(typeName: String): ActorRef =
    startSharding(
      system,
      typeName,
      entityProps = Props[ShardedEntity](),
      extractEntityId = MultiNodeClusterShardingSpec.intExtractEntityId,
      extractShardId = MultiNodeClusterShardingSpec.intExtractShardId,
      allocationStrategy =
        new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1),
      handOffStopMessage = ShardedEntity.Stop)

  lazy val region = ClusterSharding(system).shardRegion(typeName)

  s"Cluster sharding ($mode)" must {

    "start some shards in both regions" in within(30.seconds) {
      startPersistenceIfNotDdataMode(startOn = first, setStoreOn = Seq(first, second))

      join(first, first, typeName)
      join(second, first, typeName)

      awaitAssert {
        val p = TestProbe()
        val regionAddresses = (1 to 100).map { n =>
          region.tell(n, p.ref)
          p.expectMsg(1.second, n)
          p.lastSender.path.address
        }.toSet
        regionAddresses.size should be(2)
      }
      enterBarrier("after-2")
    }

    "gracefully shutdown a region" in within(30.seconds) {
      runOn(second) {
        region ! ShardRegion.GracefulShutdown
      }

      runOn(first) {
        awaitAssert {
          val p = TestProbe()
          for (n <- 1 to 200) {
            region.tell(n, p.ref)
            p.expectMsg(1.second, n)
            p.lastSender.path should be(region.path / n.toString / n.toString)
          }
        }
      }
      enterBarrier("handoff-completed")

      runOn(second) {
        watch(region)
        expectTerminated(region)
      }

      enterBarrier("after-3")
    }

    "gracefully shutdown empty region" in within(30.seconds) {
      runOn(first) {
        val regionEmpty = startSharding(typeName = "EntityEmpty")

        watch(regionEmpty)
        regionEmpty ! GracefulShutdown
        expectTerminated(regionEmpty, 5.seconds)
      }
    }

  }
}

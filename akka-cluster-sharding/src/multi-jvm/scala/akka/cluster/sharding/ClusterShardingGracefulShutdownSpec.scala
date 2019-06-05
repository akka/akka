/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._
import java.io.File

import akka.actor._
import akka.cluster.{ Cluster, MultiNodeClusterSpec }
import akka.cluster.sharding.ShardRegion.GracefulShutdown
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

object ClusterShardingGracefulShutdownSpec {
  case object StopEntity

  class Entity extends Actor {
    def receive = {
      case id: Int => sender() ! id
      case StopEntity =>
        context.stop(self)
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg =>
    msg match {
      case id: Int => id.toString
    }

}

abstract class ClusterShardingGracefulShutdownSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/ClusterShardingGracefulShutdownSpec/journal"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ClusterShardingGracefulShutdownSpec/snapshots"
    akka.cluster.sharding.state-store-mode = "$mode"
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingGracefulShutdownSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig))
}

object PersistentClusterShardingGracefulShutdownSpecConfig
    extends ClusterShardingGracefulShutdownSpecConfig("persistence")
object DDataClusterShardingGracefulShutdownSpecConfig extends ClusterShardingGracefulShutdownSpecConfig("ddata")

class PersistentClusterShardingGracefulShutdownSpec
    extends ClusterShardingGracefulShutdownSpec(PersistentClusterShardingGracefulShutdownSpecConfig)
class DDataClusterShardingGracefulShutdownSpec
    extends ClusterShardingGracefulShutdownSpec(DDataClusterShardingGracefulShutdownSpecConfig)

class PersistentClusterShardingGracefulShutdownMultiJvmNode1 extends PersistentClusterShardingGracefulShutdownSpec
class PersistentClusterShardingGracefulShutdownMultiJvmNode2 extends PersistentClusterShardingGracefulShutdownSpec

class DDataClusterShardingGracefulShutdownMultiJvmNode1 extends DDataClusterShardingGracefulShutdownSpec
class DDataClusterShardingGracefulShutdownMultiJvmNode2 extends DDataClusterShardingGracefulShutdownSpec

abstract class ClusterShardingGracefulShutdownSpec(config: ClusterShardingGracefulShutdownSpecConfig)
    extends MultiNodeSpec(config)
    with STMultiNodeSpec
    with ImplicitSender {
  import ClusterShardingGracefulShutdownSpec._
  import config._

  override def initialParticipants = roles.size

  val storageLocations = List(
    new File(system.settings.config.getString("akka.cluster.sharding.distributed-data.durable.lmdb.dir")).getParentFile)

  override protected def atStartup(): Unit = {
    storageLocations.foreach(dir => if (dir.exists) FileUtils.deleteQuietly(dir))
    enterBarrier("startup")
  }

  override protected def afterTermination(): Unit = {
    storageLocations.foreach(dir => if (dir.exists) FileUtils.deleteQuietly(dir))
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system).join(node(to).address)
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    val allocationStrategy =
      new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId,
      allocationStrategy,
      handOffStopMessage = StopEntity)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster sharding ($mode)" must {

    if (!isDdataMode) {
      "setup shared journal" in {
        // start the Persistence extension
        Persistence(system)
        runOn(first) {
          system.actorOf(Props[SharedLeveldbStore], "store")
        }
        enterBarrier("peristence-started")

        runOn(first, second) {
          system.actorSelection(node(first) / "user" / "store") ! Identify(None)
          val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
          SharedLeveldbJournal.setStore(sharedStore, system)
        }

        enterBarrier("after-1")
      }
    }

    "start some shards in both regions" in within(30.seconds) {
      join(first, first)
      join(second, first)

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
        val allocationStrategy =
          new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
        val regionEmpty = ClusterSharding(system).start(
          typeName = "EntityEmpty",
          entityProps = Props[Entity],
          settings = ClusterShardingSettings(system),
          extractEntityId = extractEntityId,
          extractShardId = extractShardId,
          allocationStrategy,
          handOffStopMessage = StopEntity)

        watch(regionEmpty)
        regionEmpty ! GracefulShutdown
        expectTerminated(regionEmpty, 5.seconds)
      }
    }

  }
}

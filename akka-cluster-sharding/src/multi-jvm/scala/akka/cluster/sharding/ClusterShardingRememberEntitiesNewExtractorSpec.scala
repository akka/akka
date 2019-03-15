/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.io.File

import akka.actor._
import akka.cluster.{ Cluster, MemberStatus, MultiNodeClusterSpec }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._

object ClusterShardingRememberEntitiesNewExtractorSpec {

  final case class Started(ref: ActorRef)

  def props(probe: Option[ActorRef]): Props = Props(new TestEntity(probe))

  class TestEntity(probe: Option[ActorRef]) extends Actor with ActorLogging {
    log.info("Entity started: " + self.path)
    probe.foreach(_ ! Started(self))

    def receive = {
      case m => sender() ! m
    }
  }

  val shardCount = 5

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId1: ShardRegion.ExtractShardId = {
    case id: Int                     => (id % shardCount).toString
    case ShardRegion.StartEntity(id) => extractShardId1(id.toInt)
  }

  val extractShardId2: ShardRegion.ExtractShardId = {
    // always bump it one shard id
    case id: Int                     => ((id + 1) % shardCount).toString
    case ShardRegion.StartEntity(id) => extractShardId2(id.toInt)
  }

}

abstract class ClusterShardingRememberEntitiesNewExtractorSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString(s"""
    akka.actor.provider = "cluster"
    akka.cluster.auto-down-unreachable-after = 0s
    akka.remote.log-remote-lifecycle-events = off
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/ShardingRememberEntitiesNewExtractorSpec/journal"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ShardingRememberEntitiesNewExtractorSpec/snapshots"
    akka.cluster.sharding.state-store-mode = "$mode"
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ShardingRememberEntitiesNewExtractorSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  val roleConfig = ConfigFactory.parseString("""
      akka.cluster.roles = [sharding]
    """)

  // we pretend node 4 and 5 are new incarnations of node 2 and 3 as they never run in parallel
  // so we can use the same lmdb store for them and have node 4 pick up the persisted data of node 2
  val ddataNodeAConfig = ConfigFactory.parseString("""
      akka.cluster.sharding.distributed-data.durable.lmdb {
        dir = target/ShardingRememberEntitiesNewExtractorSpec/sharding-node-a
      }
    """)
  val ddataNodeBConfig = ConfigFactory.parseString("""
      akka.cluster.sharding.distributed-data.durable.lmdb {
        dir = target/ShardingRememberEntitiesNewExtractorSpec/sharding-node-b
      }
    """)

  nodeConfig(second)(roleConfig.withFallback(ddataNodeAConfig))
  nodeConfig(third)(roleConfig.withFallback(ddataNodeBConfig))

}

object PersistentClusterShardingRememberEntitiesSpecNewExtractorConfig
    extends ClusterShardingRememberEntitiesNewExtractorSpecConfig(ClusterShardingSettings.StateStoreModePersistence)
object DDataClusterShardingRememberEntitiesNewExtractorSpecConfig
    extends ClusterShardingRememberEntitiesNewExtractorSpecConfig(ClusterShardingSettings.StateStoreModeDData)

class PersistentClusterShardingRememberEntitiesNewExtractorSpec
    extends ClusterShardingRememberEntitiesNewExtractorSpec(
      PersistentClusterShardingRememberEntitiesSpecNewExtractorConfig)

class PersistentClusterShardingRememberEntitiesNewExtractorMultiJvmNode1
    extends PersistentClusterShardingRememberEntitiesNewExtractorSpec
class PersistentClusterShardingRememberEntitiesNewExtractorMultiJvmNode2
    extends PersistentClusterShardingRememberEntitiesNewExtractorSpec
class PersistentClusterShardingRememberEntitiesNewExtractorMultiJvmNode3
    extends PersistentClusterShardingRememberEntitiesNewExtractorSpec

class DDataClusterShardingRememberEntitiesNewExtractorSpec
    extends ClusterShardingRememberEntitiesNewExtractorSpec(DDataClusterShardingRememberEntitiesNewExtractorSpecConfig)

class DDataClusterShardingRememberEntitiesNewExtractorMultiJvmNode1
    extends DDataClusterShardingRememberEntitiesNewExtractorSpec
class DDataClusterShardingRememberEntitiesNewExtractorMultiJvmNode2
    extends DDataClusterShardingRememberEntitiesNewExtractorSpec
class DDataClusterShardingRememberEntitiesNewExtractorMultiJvmNode3
    extends DDataClusterShardingRememberEntitiesNewExtractorSpec

abstract class ClusterShardingRememberEntitiesNewExtractorSpec(
    config: ClusterShardingRememberEntitiesNewExtractorSpecConfig)
    extends MultiNodeSpec(config)
    with STMultiNodeSpec
    with ImplicitSender {
  import ClusterShardingRememberEntitiesNewExtractorSpec._
  import config._

  val typeName = "Entity"

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
    }
    enterBarrier(from.name + "-joined")
  }

  val cluster = Cluster(system)

  def startShardingWithExtractor1(): Unit = {
    ClusterSharding(system).start(
      typeName = typeName,
      entityProps = ClusterShardingRememberEntitiesNewExtractorSpec.props(None),
      settings = ClusterShardingSettings(system).withRememberEntities(true).withRole("sharding"),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId1)
  }

  def startShardingWithExtractor2(sys: ActorSystem, probe: ActorRef): Unit = {
    ClusterSharding(sys).start(
      typeName = typeName,
      entityProps = ClusterShardingRememberEntitiesNewExtractorSpec.props(Some(probe)),
      settings = ClusterShardingSettings(system).withRememberEntities(true).withRole("sharding"),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId2)
  }

  def region(sys: ActorSystem = system) = ClusterSharding(sys).shardRegion(typeName)

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster with min-nr-of-members using sharding ($mode)" must {

    if (!isDdataMode) {
      "setup shared journal" in {
        // start the Persistence extension
        Persistence(system)
        runOn(first) {
          system.actorOf(Props[SharedLeveldbStore], "store")
        }
        enterBarrier("persistence-started")

        runOn(second, third) {
          system.actorSelection(node(first) / "user" / "store") ! Identify(None)
          val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
          SharedLeveldbJournal.setStore(sharedStore, system)
        }

        enterBarrier("after-1")
      }
    }

    "start up first cluster and sharding" in within(15.seconds) {
      join(first, first)
      join(second, first)
      join(third, first)

      runOn(first, second, third) {
        within(remaining) {
          awaitAssert {
            cluster.state.members.count(_.status == MemberStatus.Up) should ===(3)
          }
        }
      }
      runOn(second, third) {
        startShardingWithExtractor1()
      }
      enterBarrier("first-cluster-up")

      runOn(second, third) {
        // one entity for each shard id
        (1 to 10).foreach { n =>
          region() ! n
          expectMsg(n)
        }
      }
      enterBarrier("first-cluster-entities-up")
    }

    "shutdown sharding nodes" in within(30.seconds) {
      runOn(first) {
        testConductor.exit(second, 0).await
        testConductor.exit(third, 0).await
      }
      runOn(first) {
        within(remaining) {
          awaitAssert {
            cluster.state.members.count(_.status == MemberStatus.Up) should ===(1)
          }
        }
      }
      enterBarrier("first-sharding-cluster-stopped")
    }

    "start new nodes with different extractor, and have the entities running on the right shards" in within(30.seconds) {

      // start it with a new shard id extractor, which will put the entities
      // on different shards

      runOn(second, third) {
        watch(region())
        Cluster(system).leave(Cluster(system).selfAddress)
        expectTerminated(region())
        awaitAssert {
          Cluster(system).isTerminated should ===(true)
        }
      }
      enterBarrier("first-cluster-terminated")

      // no sharding nodes left of the original cluster, start a new nodes
      runOn(second, third) {
        val sys2 = ActorSystem(system.name, system.settings.config)
        val probe2 = TestProbe()(sys2)

        if (!isDdataMode) {
          sys2.actorSelection(node(first) / "user" / "store").tell(Identify(None), probe2.ref)
          val sharedStore = probe2.expectMsgType[ActorIdentity](10.seconds).ref.get
          SharedLeveldbJournal.setStore(sharedStore, sys2)
        }

        Cluster(sys2).join(node(first).address)
        startShardingWithExtractor2(sys2, probe2.ref)
        probe2.expectMsgType[Started](20.seconds)

        var stats: ShardRegion.CurrentShardRegionState = null
        within(10.seconds) {
          awaitAssert {
            region(sys2) ! ShardRegion.GetShardRegionState
            val reply = expectMsgType[ShardRegion.CurrentShardRegionState]
            reply.shards should not be empty
            stats = reply
          }
        }

        for {
          shardState <- stats.shards
          entityId <- shardState.entityIds
        } {
          val calculatedShardId = extractShardId2(entityId.toInt)
          calculatedShardId should ===(shardState.shardId)
        }

        enterBarrier("verified")
        shutdown(sys2)
      }

      runOn(first) {
        enterBarrier("verified")
      }

      enterBarrier("done")
    }

  }
}

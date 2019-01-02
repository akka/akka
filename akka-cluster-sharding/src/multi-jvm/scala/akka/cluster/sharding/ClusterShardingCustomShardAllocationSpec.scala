/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.collection.immutable
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.{ Cluster, MultiNodeClusterSpec }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy

import scala.concurrent.Future
import akka.util.Timeout
import akka.pattern.ask

object ClusterShardingCustomShardAllocationSpec {
  class Entity extends Actor {
    def receive = {
      case id: Int ⇒ sender() ! id
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int ⇒ (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case id: Int ⇒ id.toString
  }

  case object AllocateReq
  case class UseRegion(region: ActorRef)
  case object UseRegionAck
  case object RebalanceReq
  case class RebalanceShards(shards: Set[String])
  case object RebalanceShardsAck

  class Allocator extends Actor {
    var useRegion: Option[ActorRef] = None
    var rebalance = Set.empty[String]
    def receive = {
      case UseRegion(region) ⇒
        useRegion = Some(region)
        sender() ! UseRegionAck
      case AllocateReq ⇒
        useRegion.foreach { sender() ! _ }
      case RebalanceShards(shards) ⇒
        rebalance = shards
        sender() ! RebalanceShardsAck
      case RebalanceReq ⇒
        sender() ! rebalance
        rebalance = Set.empty
    }
  }

  case class TestAllocationStrategy(ref: ActorRef) extends ShardAllocationStrategy {
    implicit val timeout = Timeout(3.seconds)
    override def allocateShard(requester: ActorRef, shardId: ShardRegion.ShardId, currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardRegion.ShardId]]): Future[ActorRef] = {
      (ref ? AllocateReq).mapTo[ActorRef]
    }

    override def rebalance(currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardRegion.ShardId]], rebalanceInProgress: Set[ShardRegion.ShardId]): Future[Set[ShardRegion.ShardId]] = {
      (ref ? RebalanceReq).mapTo[Set[String]]
    }
  }

}

abstract class ClusterShardingCustomShardAllocationSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString(s"""
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/ClusterShardingCustomShardAllocationSpec/journal"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ClusterShardingCustomShardAllocationSpec/snapshots"
    akka.cluster.sharding.state-store-mode = "$mode"
    akka.cluster.sharding.rebalance-interval = 1 s
    #akka.cluster.sharding.retry-interval = 5 s
    """).withFallback(MultiNodeClusterSpec.clusterConfig))
}

object PersistentClusterShardingCustomShardAllocationSpecConfig extends ClusterShardingCustomShardAllocationSpecConfig("persistence")
object DDataClusterShardingCustomShardAllocationSpecConfig extends ClusterShardingCustomShardAllocationSpecConfig("ddata")

class PersistentClusterShardingCustomShardAllocationSpec extends ClusterShardingCustomShardAllocationSpec(PersistentClusterShardingCustomShardAllocationSpecConfig)
class DDataClusterShardingCustomShardAllocationSpec extends ClusterShardingCustomShardAllocationSpec(DDataClusterShardingCustomShardAllocationSpecConfig)

class PersistentClusterShardingCustomShardAllocationMultiJvmNode1 extends PersistentClusterShardingCustomShardAllocationSpec
class PersistentClusterShardingCustomShardAllocationMultiJvmNode2 extends PersistentClusterShardingCustomShardAllocationSpec

class DDataClusterShardingCustomShardAllocationMultiJvmNode1 extends DDataClusterShardingCustomShardAllocationSpec
class DDataClusterShardingCustomShardAllocationMultiJvmNode2 extends DDataClusterShardingCustomShardAllocationSpec

abstract class ClusterShardingCustomShardAllocationSpec(config: ClusterShardingCustomShardAllocationSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingCustomShardAllocationSpec._
  import config._

  override def initialParticipants = roles.size

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId,
      allocationStrategy = TestAllocationStrategy(allocator),
      handOffStopMessage = PoisonPill)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  lazy val allocator = system.actorOf(Props[Allocator], "allocator")

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster sharding ($mode) with custom allocation strategy" must {

    if (!isDdataMode) {
      "setup shared journal" in {
        // start the Persistence extension
        Persistence(system)
        runOn(first) {
          system.actorOf(Props[SharedLeveldbStore], "store")
        }
        enterBarrier("persistence-started")

        runOn(first, second) {
          system.actorSelection(node(first) / "user" / "store") ! Identify(None)
          val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
          SharedLeveldbJournal.setStore(sharedStore, system)
        }

        enterBarrier("after-1")
      }
    }

    "use specified region" in within(30.seconds) {
      join(first, first)

      runOn(first) {
        allocator ! UseRegion(region)
        expectMsg(UseRegionAck)
        region ! 1
        expectMsg(1)
        lastSender.path should be(region.path / "1" / "1")
      }
      enterBarrier("first-started")

      join(second, first)

      region ! 2
      expectMsg(2)
      runOn(first) {
        lastSender.path should be(region.path / "2" / "2")
      }
      runOn(second) {
        lastSender.path should be(node(first) / "system" / "sharding" / "Entity" / "2" / "2")
      }
      enterBarrier("second-started")

      runOn(first) {
        system.actorSelection(node(second) / "system" / "sharding" / "Entity") ! Identify(None)
        val secondRegion = expectMsgType[ActorIdentity].ref.get
        allocator ! UseRegion(secondRegion)
        expectMsg(UseRegionAck)
      }
      enterBarrier("second-active")

      region ! 3
      expectMsg(3)
      runOn(second) {
        lastSender.path should be(region.path / "3" / "3")
      }
      runOn(first) {
        lastSender.path should be(node(second) / "system" / "sharding" / "Entity" / "3" / "3")
      }

      enterBarrier("after-2")
    }

    "rebalance specified shards" in within(15.seconds) {
      runOn(first) {
        allocator ! RebalanceShards(Set("2"))
        expectMsg(RebalanceShardsAck)

        awaitAssert {
          val p = TestProbe()
          region.tell(2, p.ref)
          p.expectMsg(2.second, 2)
          p.lastSender.path should be(node(second) / "system" / "sharding" / "Entity" / "2" / "2")
        }

        region ! 1
        expectMsg(1)
        lastSender.path should be(region.path / "1" / "1")
      }

      enterBarrier("after-2")
    }

  }
}

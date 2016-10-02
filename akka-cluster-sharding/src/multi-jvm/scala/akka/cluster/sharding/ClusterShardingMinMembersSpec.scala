/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.duration._
import java.io.File

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.sharding.ShardRegion.GracefulShutdown
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.{ SharedLeveldbJournal, SharedLeveldbStore }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

import scala.concurrent.duration._
import akka.cluster.sharding.ShardRegion.GetClusterShardingStats
import akka.cluster.sharding.ShardRegion.ClusterShardingStats
import akka.cluster.MemberStatus

object ClusterShardingMinMembersSpec {
  case object StopEntity

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int ⇒ (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg ⇒ msg match {
    case id: Int ⇒ id.toString
  }

}

abstract class ClusterShardingMinMembersSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/journal-ClusterShardingMinMembersSpec"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingMinMembersSpec"
    akka.cluster.sharding.state-store-mode = "$mode"
    akka.cluster.sharding.rebalance-interval = 120s #disable rebalance
    akka.cluster.min-nr-of-members = 3
    """))
}

object PersistentClusterShardingMinMembersSpecConfig extends ClusterShardingMinMembersSpecConfig("persistence")
object DDataClusterShardingMinMembersSpecConfig extends ClusterShardingMinMembersSpecConfig("ddata")

class PersistentClusterShardingMinMembersSpec extends ClusterShardingMinMembersSpec(PersistentClusterShardingMinMembersSpecConfig)
class DDataClusterShardingMinMembersSpec extends ClusterShardingMinMembersSpec(DDataClusterShardingMinMembersSpecConfig)

class PersistentClusterShardingMinMembersMultiJvmNode1 extends PersistentClusterShardingMinMembersSpec
class PersistentClusterShardingMinMembersMultiJvmNode2 extends PersistentClusterShardingMinMembersSpec
class PersistentClusterShardingMinMembersMultiJvmNode3 extends PersistentClusterShardingMinMembersSpec

class DDataClusterShardingMinMembersMultiJvmNode1 extends DDataClusterShardingMinMembersSpec
class DDataClusterShardingMinMembersMultiJvmNode2 extends DDataClusterShardingMinMembersSpec
class DDataClusterShardingMinMembersMultiJvmNode3 extends DDataClusterShardingMinMembersSpec

abstract class ClusterShardingMinMembersSpec(config: ClusterShardingMinMembersSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingMinMembersSpec._
  import config._

  override def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(first) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(first) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  val cluster = Cluster(system)

  def startSharding(): Unit = {
    val allocationStrategy = new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = TestActors.echoActorProps,
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId,
      allocationStrategy,
      handOffStopMessage = StopEntity)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  s"Cluster with min-nr-of-members using sharding ($mode)" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(first) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(first, second, third) {
        system.actorSelection(node(first) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "use all nodes" in within(30.seconds) {
      join(first, first)
      runOn(first) {
        startSharding()
      }
      join(second, first)
      runOn(second) {
        startSharding()
      }
      join(third, first)
      // wait with starting sharding on third
      within(remaining) {
        awaitAssert {
          cluster.state.members.size should ===(3)
          cluster.state.members.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }
      enterBarrier("all-up")

      runOn(first) {
        region ! 1
        // not allocated because third has not registered yet
        expectNoMsg(2.second)
      }
      enterBarrier("verified")

      runOn(third) {
        startSharding()
      }

      runOn(first) {
        // the 1 was sent above
        expectMsg(1)
        region ! 2
        expectMsg(2)
        region ! 3
        expectMsg(3)
      }
      enterBarrier("shards-allocated")

      region ! GetClusterShardingStats(remaining)
      val stats = expectMsgType[ClusterShardingStats]
      val firstAddress = node(first).address
      val secondAddress = node(second).address
      val thirdAddress = node(third).address
      withClue(stats) {
        stats.regions.keySet should ===(Set(firstAddress, secondAddress, thirdAddress))
        stats.regions(firstAddress).stats.valuesIterator.sum should ===(1)
      }
      enterBarrier("after-2")
    }

  }
}


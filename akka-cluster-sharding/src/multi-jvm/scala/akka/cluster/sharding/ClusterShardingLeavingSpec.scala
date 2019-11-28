/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.io.File

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.cluster.{ Cluster, MemberStatus, MultiNodeClusterSpec }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.serialization.jackson.CborSerializable
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

object ClusterShardingLeavingSpec {
  case class Ping(id: String) extends CborSerializable

  class Entity extends Actor {
    def receive = {
      case Ping(_) => sender() ! self
    }
  }

  case object GetLocations extends CborSerializable
  case class Locations(locations: Map[String, ActorRef]) extends CborSerializable

  class ShardLocations extends Actor {
    var locations: Locations = _
    def receive = {
      case GetLocations => sender() ! locations
      case l: Locations => locations = l
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case m @ Ping(id) => (id, m)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id: String) => id.charAt(0).toString
  }
}

abstract class ClusterShardingLeavingSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(
    ConfigFactory
      .parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.classic.log-remote-lifecycle-events = off
    akka.cluster.downing-provider-class = akka.cluster.testkit.AutoDowning
    akka.cluster.testkit.auto-down-unreachable-after = 0s
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/ClusterShardingLeavingSpec/journal"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ClusterShardingLeavingSpec/snapshots"
    akka.cluster.sharding.state-store-mode = "$mode"
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingLeavingSpec/sharding-ddata
      map-size = 10 MiB
    }
    """)
      .withFallback(SharedLeveldbJournal.configToEnableJavaSerializationForTest)
      .withFallback(MultiNodeClusterSpec.clusterConfig))
}

object PersistentClusterShardingLeavingSpecConfig extends ClusterShardingLeavingSpecConfig("persistence")
object DDataClusterShardingLeavingSpecConfig extends ClusterShardingLeavingSpecConfig("ddata")

class PersistentClusterShardingLeavingSpec
    extends ClusterShardingLeavingSpec(PersistentClusterShardingLeavingSpecConfig)
class DDataClusterShardingLeavingSpec extends ClusterShardingLeavingSpec(DDataClusterShardingLeavingSpecConfig)

class PersistentClusterShardingLeavingMultiJvmNode1 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode2 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode3 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode4 extends PersistentClusterShardingLeavingSpec

class DDataClusterShardingLeavingMultiJvmNode1 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode2 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode3 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode4 extends DDataClusterShardingLeavingSpec

abstract class ClusterShardingLeavingSpec(config: ClusterShardingLeavingSpecConfig)
    extends MultiNodeSpec(config)
    with STMultiNodeSpec
    with ImplicitSender {
  import ClusterShardingLeavingSpec._
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

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster.join(node(to).address)
      startSharding()
      within(15.seconds) {
        awaitAssert(cluster.state.members.exists { m =>
          m.uniqueAddress == cluster.selfUniqueAddress && m.status == MemberStatus.Up
        } should be(true))
      }
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster sharding ($mode) with leaving member" must {

    if (!isDdataMode) {
      "setup shared journal" in {
        // start the Persistence extension
        Persistence(system)
        runOn(first) {
          system.actorOf(Props[SharedLeveldbStore], "store")
        }
        enterBarrier("peristence-started")

        system.actorSelection(node(first) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)

        enterBarrier("after-1")
      }
    }

    "join cluster" in within(20.seconds) {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)

      enterBarrier("after-2")
    }

    "initialize shards" in {
      runOn(first) {
        val shardLocations = system.actorOf(Props[ShardLocations], "shardLocations")
        val locations = (for (n <- 1 to 10) yield {
          val id = n.toString
          region ! Ping(id)
          id -> expectMsgType[ActorRef]
        }).toMap
        shardLocations ! Locations(locations)
      }
      enterBarrier("after-3")
    }

    "recover after leaving coordinator node" in {
      system.actorSelection(node(first) / "user" / "shardLocations") ! GetLocations
      val Locations(originalLocations) = expectMsgType[Locations]
      val firstAddress = node(first).address

      runOn(third) {
        cluster.leave(node(first).address)
      }

      runOn(first) {
        watch(region)
        expectTerminated(region, 15.seconds)
      }
      enterBarrier("stopped")

      runOn(second, third, fourth) {
        within(15.seconds) {
          awaitAssert {
            val probe = TestProbe()
            originalLocations.foreach {
              case (id, ref) =>
                region.tell(Ping(id), probe.ref)
                if (ref.path.address == firstAddress)
                  probe.expectMsgType[ActorRef](1.second) should not be (ref)
                else
                  probe.expectMsg(1.second, ref) // should not move
            }
          }
        }
      }

      enterBarrier("after-4")
    }

  }
}

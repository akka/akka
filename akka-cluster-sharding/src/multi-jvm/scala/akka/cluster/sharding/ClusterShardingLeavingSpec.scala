/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import java.io.File

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils

object ClusterShardingLeavingSpec {
  case class Ping(id: String)

  class Entity extends Actor {
    def receive = {
      case Ping(_) ⇒ sender() ! self
    }
  }

  case object GetLocations
  case class Locations(locations: Map[String, ActorRef])

  class ShardLocations extends Actor {
    var locations: Locations = _
    def receive = {
      case GetLocations ⇒ sender() ! locations
      case l: Locations ⇒ locations = l
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case m @ Ping(id) ⇒ (id, m)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id: String) ⇒ id.charAt(0).toString
  }
}

abstract class ClusterShardingLeavingSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/journal-ClusterShardingLeavingSpec"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingLeavingSpec"
    akka.cluster.sharding.state-store-mode = "$mode"
    """))
}

object PersistentClusterShardingLeavingSpecConfig extends ClusterShardingLeavingSpecConfig("persistence")
object DDataClusterShardingLeavingSpecConfig extends ClusterShardingLeavingSpecConfig("ddata")

class PersistentClusterShardingLeavingSpec extends ClusterShardingLeavingSpec(PersistentClusterShardingLeavingSpecConfig)
class DDataClusterShardingLeavingSpec extends ClusterShardingLeavingSpec(DDataClusterShardingLeavingSpecConfig)

class PersistentClusterShardingLeavingMultiJvmNode1 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode2 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode3 extends PersistentClusterShardingLeavingSpec
class PersistentClusterShardingLeavingMultiJvmNode4 extends PersistentClusterShardingLeavingSpec

class DDataClusterShardingLeavingMultiJvmNode1 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode2 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode3 extends DDataClusterShardingLeavingSpec
class DDataClusterShardingLeavingMultiJvmNode4 extends DDataClusterShardingLeavingSpec

abstract class ClusterShardingLeavingSpec(config: ClusterShardingLeavingSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingLeavingSpec._
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

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
      startSharding()
      within(15.seconds) {
        awaitAssert(cluster.state.members.exists { m ⇒
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

  s"Cluster sharding ($mode) with leaving member" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(first) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      system.actorSelection(node(first) / "user" / "store") ! Identify(None)
      val sharedStore = expectMsgType[ActorIdentity].ref.get
      SharedLeveldbJournal.setStore(sharedStore, system)

      enterBarrier("after-1")
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
        val locations = (for (n ← 1 to 10) yield {
          val id = n.toString
          region ! Ping(id)
          id -> expectMsgType[ActorRef]
        }).toMap
        shardLocations ! Locations(locations)
      }
      enterBarrier("after-3")
    }

    "recover after leaving coordinator node" in within(30.seconds) {
      runOn(third) {
        cluster.leave(node(first).address)
      }

      runOn(first) {
        watch(region)
        expectTerminated(region, 15.seconds)
      }
      enterBarrier("stopped")

      runOn(second, third, fourth) {
        system.actorSelection(node(first) / "user" / "shardLocations") ! GetLocations
        val Locations(locations) = expectMsgType[Locations]
        val firstAddress = node(first).address
        awaitAssert {
          val probe = TestProbe()
          locations.foreach {
            case (id, ref) ⇒
              region.tell(Ping(id), probe.ref)
              if (ref.path.address == firstAddress)
                probe.expectMsgType[ActorRef](1.second) should not be (ref)
              else
                probe.expectMsg(1.second, ref) // should not move
          }
        }
      }

      enterBarrier("after-4")
    }

  }
}


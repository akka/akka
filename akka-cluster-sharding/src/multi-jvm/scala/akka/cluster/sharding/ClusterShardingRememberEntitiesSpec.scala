/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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

object ClusterShardingRememberEntitiesSpec {

  final case class Started(ref: ActorRef)

  def props(probe: ActorRef): Props = Props(new TestEntity(probe))

  class TestEntity(probe: ActorRef) extends Actor {
    probe ! Started(self)

    def receive = {
      case m ⇒ sender() ! m
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int ⇒ (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg ⇒ msg match {
    case id: Int ⇒ id.toString
  }

}

abstract class ClusterShardingRememberEntitiesSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.cluster.auto-down-unreachable-after = 0s
    akka.remote.log-remote-lifecycle-events = off
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/journal-ClusterShardingRememberEntitiesSpec"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingRememberEntitiesSpec"
    akka.cluster.sharding.state-store-mode = "$mode"
    """))
}

object PersistentClusterShardingRememberEntitiesSpecConfig extends ClusterShardingRememberEntitiesSpecConfig("persistence")
object DDataClusterShardingRememberEntitiesSpecConfig extends ClusterShardingRememberEntitiesSpecConfig("ddata")

class PersistentClusterShardingRememberEntitiesSpec extends ClusterShardingRememberEntitiesSpec(PersistentClusterShardingRememberEntitiesSpecConfig)

class PersistentClusterShardingRememberEntitiesMultiJvmNode1 extends PersistentClusterShardingRememberEntitiesSpec
class PersistentClusterShardingRememberEntitiesMultiJvmNode2 extends PersistentClusterShardingRememberEntitiesSpec
class PersistentClusterShardingRememberEntitiesMultiJvmNode3 extends PersistentClusterShardingRememberEntitiesSpec

abstract class ClusterShardingRememberEntitiesSpec(config: ClusterShardingRememberEntitiesSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingRememberEntitiesSpec._
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
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = ClusterShardingRememberEntitiesSpec.props(testActor),
      settings = ClusterShardingSettings(system).withRememberEntities(true),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
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

      runOn(second, third) {
        system.actorSelection(node(first) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "start remembered entities when coordinator fail over" in within(30.seconds) {
      join(second, second)
      runOn(second) {
        startSharding()
        region ! 1
        expectMsgType[Started]
      }

      join(third, second)
      runOn(third) {
        startSharding()
      }
      runOn(second, third) {
        within(remaining) {
          awaitAssert {
            cluster.state.members.size should ===(2)
            cluster.state.members.map(_.status) should ===(Set(MemberStatus.Up))
          }
        }
      }
      enterBarrier("all-up")

      runOn(first) {
        testConductor.exit(second, 0).await
      }
      enterBarrier("crash-second")

      runOn(third) {
        expectMsgType[Started](remaining)
      }

      enterBarrier("after-2")
    }

  }
}


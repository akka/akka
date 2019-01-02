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
    case id: Int                     ⇒ id.toString
    case ShardRegion.StartEntity(id) ⇒ id
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
        dir = "target/ShardingRememberEntitiesSpec/journal"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ShardingRememberEntitiesSpec/snapshots"
    akka.cluster.sharding.state-store-mode = "$mode"
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ShardingRememberEntitiesSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(third)(ConfigFactory.parseString(s"""
    akka.cluster.sharding.distributed-data.durable.lmdb {
      # use same directory when starting new node on third (not used at same time)
      dir = target/ShardingRememberEntitiesSpec/sharding-third
    }
    """))
}

object PersistentClusterShardingRememberEntitiesSpecConfig extends ClusterShardingRememberEntitiesSpecConfig(
  ClusterShardingSettings.StateStoreModePersistence)
object DDataClusterShardingRememberEntitiesSpecConfig extends ClusterShardingRememberEntitiesSpecConfig(
  ClusterShardingSettings.StateStoreModeDData)

class PersistentClusterShardingRememberEntitiesSpec extends ClusterShardingRememberEntitiesSpec(
  PersistentClusterShardingRememberEntitiesSpecConfig)

class PersistentClusterShardingRememberEntitiesMultiJvmNode1 extends PersistentClusterShardingRememberEntitiesSpec
class PersistentClusterShardingRememberEntitiesMultiJvmNode2 extends PersistentClusterShardingRememberEntitiesSpec
class PersistentClusterShardingRememberEntitiesMultiJvmNode3 extends PersistentClusterShardingRememberEntitiesSpec

class DDataClusterShardingRememberEntitiesSpec extends ClusterShardingRememberEntitiesSpec(
  DDataClusterShardingRememberEntitiesSpecConfig)

class DDataClusterShardingRememberEntitiesMultiJvmNode1 extends DDataClusterShardingRememberEntitiesSpec
class DDataClusterShardingRememberEntitiesMultiJvmNode2 extends DDataClusterShardingRememberEntitiesSpec
class DDataClusterShardingRememberEntitiesMultiJvmNode3 extends DDataClusterShardingRememberEntitiesSpec

abstract class ClusterShardingRememberEntitiesSpec(config: ClusterShardingRememberEntitiesSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingRememberEntitiesSpec._
  import config._

  override def initialParticipants = roles.size

  val storageLocations = List(new File(system.settings.config.getString(
    "akka.cluster.sharding.distributed-data.durable.lmdb.dir")).getParentFile)

  override protected def atStartup(): Unit = {
    storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteQuietly(dir))
    enterBarrier("startup")
  }

  override protected def afterTermination(): Unit = {
    storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteQuietly(dir))
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  val cluster = Cluster(system)

  def startSharding(sys: ActorSystem = system, probe: ActorRef = testActor): Unit = {
    ClusterSharding(sys).start(
      typeName = "Entity",
      entityProps = ClusterShardingRememberEntitiesSpec.props(probe),
      settings = ClusterShardingSettings(system).withRememberEntities(true),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster sharding with remember entities ($mode)" must {

    if (!isDdataMode) {
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
    }

    "start remembered entities when coordinator fail over" in within(30.seconds) {
      join(second, second)
      runOn(second) {
        startSharding()
        region ! 1
        expectMsgType[Started]
      }
      enterBarrier("second-started")

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
        if (isDdataMode) {
          // Entity 1 in region of first node was started when there was only one node
          // and then the remembering state will be replicated to second node by the
          // gossip. So we must give that a chance to replicate before shutting down second.
          Thread.sleep(5000)
        }
        testConductor.exit(second, 0).await
      }
      enterBarrier("crash-second")

      runOn(third) {
        expectMsgType[Started](remaining)
      }

      enterBarrier("after-2")
    }

    "start remembered entities in new cluster" in within(30.seconds) {
      runOn(third) {
        watch(region)
        Cluster(system).leave(Cluster(system).selfAddress)
        expectTerminated(region)
        awaitAssert {
          Cluster(system).isTerminated should ===(true)
        }
        // no nodes left of the original cluster, start a new cluster

        val sys2 = ActorSystem(system.name, system.settings.config)
        val probe2 = TestProbe()(sys2)

        if (!isDdataMode) {
          sys2.actorSelection(node(first) / "user" / "store").tell(Identify(None), probe2.ref)
          val sharedStore = probe2.expectMsgType[ActorIdentity](10.seconds).ref.get
          SharedLeveldbJournal.setStore(sharedStore, sys2)
        }

        Cluster(sys2).join(Cluster(sys2).selfAddress)
        startSharding(sys2, probe2.ref)
        probe2.expectMsgType[Started](20.seconds)

        shutdown(sys2)
      }
      enterBarrier("after-3")
    }
  }
}


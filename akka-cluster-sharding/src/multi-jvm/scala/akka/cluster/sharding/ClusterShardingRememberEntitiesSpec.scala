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
import akka.util.ccompat._

import scala.concurrent.duration._

@ccompatUsedUntil213
object ClusterShardingRememberEntitiesSpec {

  final case class Started(ref: ActorRef)

  def props(probe: ActorRef): Props = Props(new TestEntity(probe))

  class TestEntity(probe: ActorRef) extends Actor {
    probe ! Started(self)

    def receive = {
      case m => sender() ! m
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg =>
    msg match {
      case id: Int                     => id.toString
      case ShardRegion.StartEntity(id) => id
    }

}

abstract class ClusterShardingRememberEntitiesSpecConfig(val mode: String, val rememberEntities: Boolean)
    extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  val targetDir = s"target/ClusterShardingRememberEntitiesSpec-$mode-remember-$rememberEntities"

  val modeConfig =
    if (mode == ClusterShardingSettings.StateStoreModeDData) ConfigFactory.empty
    else ConfigFactory.parseString(s"""
      akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
      akka.persistence.journal.leveldb-shared.timeout = 5s
      akka.persistence.journal.leveldb-shared.store.native = off
      akka.persistence.journal.leveldb-shared.store.dir = "$targetDir/journal"
      akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
      akka.persistence.snapshot-store.local.dir = "$targetDir/snapshots"
      """)

  commonConfig(
    modeConfig
      .withFallback(ConfigFactory.parseString(s"""
      akka.actor.provider = "cluster"
      akka.cluster.downing-provider-class = akka.cluster.testkit.AutoDowning
      akka.cluster.testkit.auto-down-unreachable-after = 0s
      akka.remote.log-remote-lifecycle-events = off
      akka.cluster.sharding.state-store-mode = "$mode"
      akka.cluster.sharding.distributed-data.durable.lmdb {
        dir = $targetDir/sharding-ddata
        map-size = 10 MiB
      }
      akka.testconductor.barrier-timeout = 60 s
      akka.test.single-expect-default = 60 s
      """))
      .withFallback(SharedLeveldbJournal.configToEnableJavaSerializationForTest)
      .withFallback(MultiNodeClusterSpec.clusterConfig))

  nodeConfig(third)(ConfigFactory.parseString(s"""
    akka.cluster.sharding.distributed-data.durable.lmdb {
      # use same directory when starting new node on third (not used at same time)
      dir = $targetDir/sharding-third
    }
    """))

}

class PersistentClusterShardingRememberEntitiesSpecConfig(rememberEntities: Boolean)
    extends ClusterShardingRememberEntitiesSpecConfig(
      ClusterShardingSettings.StateStoreModePersistence,
      rememberEntities)
class DDataClusterShardingRememberEntitiesSpecConfig(rememberEntities: Boolean)
    extends ClusterShardingRememberEntitiesSpecConfig(ClusterShardingSettings.StateStoreModeDData, rememberEntities)

abstract class PersistentClusterShardingRememberEntitiesSpec(val rememberEntities: Boolean)
    extends ClusterShardingRememberEntitiesSpec(
      new PersistentClusterShardingRememberEntitiesSpecConfig(rememberEntities))
abstract class DDataClusterShardingRememberEntitiesSpec(val rememberEntities: Boolean)
    extends ClusterShardingRememberEntitiesSpec(new DDataClusterShardingRememberEntitiesSpecConfig(rememberEntities))

class PersistentClusterShardingRememberEntitiesEnabledMultiJvmNode1
    extends PersistentClusterShardingRememberEntitiesSpec(true)
class PersistentClusterShardingRememberEntitiesEnabledMultiJvmNode2
    extends PersistentClusterShardingRememberEntitiesSpec(true)
class PersistentClusterShardingRememberEntitiesEnabledMultiJvmNode3
    extends PersistentClusterShardingRememberEntitiesSpec(true)

class PersistentClusterShardingRememberEntitiesDefaultMultiJvmNode1
    extends PersistentClusterShardingRememberEntitiesSpec(false)
class PersistentClusterShardingRememberEntitiesDefaultMultiJvmNode2
    extends PersistentClusterShardingRememberEntitiesSpec(false)
class PersistentClusterShardingRememberEntitiesDefaultMultiJvmNode3
    extends PersistentClusterShardingRememberEntitiesSpec(false)

class DDataClusterShardingRememberEntitiesEnabledMultiJvmNode1 extends DDataClusterShardingRememberEntitiesSpec(true)
class DDataClusterShardingRememberEntitiesEnabledMultiJvmNode2 extends DDataClusterShardingRememberEntitiesSpec(true)
class DDataClusterShardingRememberEntitiesEnabledMultiJvmNode3 extends DDataClusterShardingRememberEntitiesSpec(true)

class DDataClusterShardingRememberEntitiesDefaultMultiJvmNode1 extends DDataClusterShardingRememberEntitiesSpec(false)
class DDataClusterShardingRememberEntitiesDefaultMultiJvmNode2 extends DDataClusterShardingRememberEntitiesSpec(false)
class DDataClusterShardingRememberEntitiesDefaultMultiJvmNode3 extends DDataClusterShardingRememberEntitiesSpec(false)

abstract class ClusterShardingRememberEntitiesSpec(config: ClusterShardingRememberEntitiesSpecConfig)
    extends MultiNodeSpec(config)
    with STMultiNodeSpec
    with ImplicitSender {
  import ClusterShardingRememberEntitiesSpec._
  import config._

  override def initialParticipants: Int = roles.size

  val dataType = "Entity"

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
      awaitAssert {
        Cluster(system).state.isMemberUp(node(from).address)
      }
    }
    enterBarrier(from.name + "-joined")
  }

  val cluster = Cluster(system)

  def startSharding(sys: ActorSystem, probe: ActorRef): ActorRef = {
    ClusterSharding(sys).start(
      typeName = dataType,
      entityProps = ClusterShardingRememberEntitiesSpec.props(probe),
      settings = ClusterShardingSettings(sys).withRememberEntities(rememberEntities),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion(dataType)

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  def expectEntityRestarted(sys: ActorSystem, event: Int, probe: TestProbe, entityProbe: TestProbe): Started = {
    if (!rememberEntities) {
      probe.send(ClusterSharding(sys).shardRegion(dataType), event)
      probe.expectMsg(1)
    }

    entityProbe.expectMsgType[Started](30.seconds)
  }

  def setStoreIfNotDdata(sys: ActorSystem): Unit =
    if (!isDdataMode) {
      val probe = TestProbe()(sys)
      sys.actorSelection(node(first) / "user" / "store").tell(Identify(None), probe.ref)
      val sharedStore = probe.expectMsgType[ActorIdentity](20.seconds).ref.get
      SharedLeveldbJournal.setStore(sharedStore, sys)
    }

  s"Cluster sharding with remember entities ($mode)" must {

    if (!isDdataMode) {
      "setup shared journal" in {
        // start the Persistence extension
        Persistence(system)
        runOn(first) {
          system.actorOf(Props[SharedLeveldbStore], "store")
        }
        enterBarrier("persistence-started")

        runOn(first, second, third) {
          setStoreIfNotDdata(system)
        }

        enterBarrier("after-1")
      }
    }

    "start remembered entities when coordinator fail over" in within(30.seconds) {
      val entityProbe = TestProbe()
      val probe = TestProbe()
      join(second, second)
      runOn(second) {
        startSharding(system, entityProbe.ref)
        probe.send(region, 1)
        probe.expectMsg(1)
        entityProbe.expectMsgType[Started]
      }
      enterBarrier("second-started")

      join(third, second)
      runOn(third) {
        startSharding(system, entityProbe.ref)
      }
      runOn(second, third) {
        within(remaining) {
          awaitAssert {
            cluster.state.members.size should ===(2)
            cluster.state.members.unsorted.map(_.status) should ===(Set(MemberStatus.Up))
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
        expectEntityRestarted(system, 1, probe, entityProbe)
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
        val entityProbe2 = TestProbe()(sys2)
        val probe2 = TestProbe()(sys2)

        setStoreIfNotDdata(sys2)

        Cluster(sys2).join(Cluster(sys2).selfAddress)

        startSharding(sys2, entityProbe2.ref)

        expectEntityRestarted(sys2, 1, probe2, entityProbe2)

        shutdown(sys2)
      }
      enterBarrier("after-3")
    }
  }
}

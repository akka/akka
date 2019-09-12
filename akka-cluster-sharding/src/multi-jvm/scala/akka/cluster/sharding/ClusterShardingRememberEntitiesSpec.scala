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
      akka.cluster.sharding.journal-plugin-id = "akka.persistence.journal.inmem"
      """)

  commonConfig(
    modeConfig
      .withFallback(ConfigFactory.parseString(s"""
      akka.loglevel = DEBUG
      akka.actor.provider = "cluster"
      akka.cluster.auto-down-unreachable-after = 0s
      akka.remote.log-remote-lifecycle-events = off
      akka.cluster.sharding.state-store-mode = "$mode"
      akka.cluster.sharding.distributed-data.durable.lmdb {
        dir = $targetDir/sharding-ddata
        map-size = 10 MiB
      }
      # FIXME temporary while sanity-checking persistence enabled
      # waiting for `Started` from restarted entity
      akka.testconductor.barrier-timeout = 60 s
      akka.test.single-expect-default = 60 s # defaults to 3s
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

object PersistentClusterShardingRememberEntitiesEnabledSpecConfig
    extends ClusterShardingRememberEntitiesSpecConfig(ClusterShardingSettings.StateStoreModePersistence, true)
object PersistentClusterShardingRememberEntitiesDefaultSpecConfig
    extends ClusterShardingRememberEntitiesSpecConfig(ClusterShardingSettings.StateStoreModePersistence, false)
object DDataClusterShardingRememberEntitiesEnabledSpecConfig
    extends ClusterShardingRememberEntitiesSpecConfig(ClusterShardingSettings.StateStoreModeDData, true)
object DDataClusterShardingRememberEntitiesDefaultSpecConfig
    extends ClusterShardingRememberEntitiesSpecConfig(ClusterShardingSettings.StateStoreModeDData, false)

class PersistentClusterShardingRememberEntitiesEnabledSpec
    extends ClusterShardingRememberEntitiesSpec(PersistentClusterShardingRememberEntitiesEnabledSpecConfig)
class PersistentClusterShardingRememberEntitiesDefaultSpec
    extends ClusterShardingRememberEntitiesSpec(PersistentClusterShardingRememberEntitiesDefaultSpecConfig)

class PersistentClusterShardingRememberEntitiesEnabledMultiJvmNode1
    extends PersistentClusterShardingRememberEntitiesEnabledSpec
class PersistentClusterShardingRememberEntitiesEnabledMultiJvmNode2
    extends PersistentClusterShardingRememberEntitiesEnabledSpec
class PersistentClusterShardingRememberEntitiesEnabledMultiJvmNode3
    extends PersistentClusterShardingRememberEntitiesEnabledSpec

class PersistentClusterShardingRememberEntitiesDefaultMultiJvmNode1
    extends PersistentClusterShardingRememberEntitiesDefaultSpec
class PersistentClusterShardingRememberEntitiesDefaultMultiJvmNode2
    extends PersistentClusterShardingRememberEntitiesDefaultSpec
class PersistentClusterShardingRememberEntitiesDefaultMultiJvmNode3
    extends PersistentClusterShardingRememberEntitiesDefaultSpec

class DDataClusterShardingRememberEntitiesEnabledSpec
    extends ClusterShardingRememberEntitiesSpec(DDataClusterShardingRememberEntitiesEnabledSpecConfig)
class DDataClusterShardingRememberEntitiesDefaultSpec
    extends ClusterShardingRememberEntitiesSpec(DDataClusterShardingRememberEntitiesEnabledSpecConfig)

class DDataClusterShardingRememberEntitiesEnabledMultiJvmNode1 extends DDataClusterShardingRememberEntitiesEnabledSpec
class DDataClusterShardingRememberEntitiesEnabledMultiJvmNode2 extends DDataClusterShardingRememberEntitiesEnabledSpec
class DDataClusterShardingRememberEntitiesEnabledMultiJvmNode3 extends DDataClusterShardingRememberEntitiesEnabledSpec

class DDataClusterShardingRememberEntitiesDisabledMultiJvmNode1 extends DDataClusterShardingRememberEntitiesEnabledSpec
class DDataClusterShardingRememberEntitiesDisabledMultiJvmNode2 extends DDataClusterShardingRememberEntitiesEnabledSpec
class DDataClusterShardingRememberEntitiesDisabledMultiJvmNode3 extends DDataClusterShardingRememberEntitiesEnabledSpec

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
      join(second, second)
      runOn(second) {
        startSharding(system, testActor)
        region ! 1
        expectMsgType[Started]
      }
      enterBarrier("second-started")

      join(third, second)
      runOn(third) {
        startSharding(system, testActor)
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
        if (!rememberEntities) {
          ClusterSharding(system).shardRegion(dataType) ! 1
        }
        // FIXME This is where we fail if remember=true, mode=persistence.
        // What is curious is after node 'second' stops
        // the ShardCoordinator does correctly restart on node 'third',
        // however the entity does not get re-created automatically as it should, even though:
        // the ShardRegion
        // - does get registered: [Actor[akka://ClusterShardingRememberEntitiesSpec/system/sharding/Entity#380407716]]
        // - is in the ShardCoordinator's 'aliveRegion'
        // - does receive the RegisterAck
        expectMsgType[Started](20.seconds)
        if (!rememberEntities) {
          expectMsg(1)
        }
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

        setStoreIfNotDdata(sys2)

        Cluster(sys2).join(Cluster(sys2).selfAddress)
        startSharding(sys2, probe2.ref)

        if (!rememberEntities) {
          probe2.send(ClusterSharding(sys2).shardRegion(dataType), 1)
        }
        probe2.expectMsgType[Started](20.seconds)
        if (!rememberEntities) {
          probe2.expectMsg(1)
        }

        shutdown(sys2)
      }
      enterBarrier("after-3")
    }
  }
}

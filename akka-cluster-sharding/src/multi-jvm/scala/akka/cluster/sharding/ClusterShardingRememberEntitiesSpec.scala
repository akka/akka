/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor._
import akka.cluster.{ Cluster, MemberStatus }
import akka.testkit._
import akka.util.ccompat._
import com.typesafe.config.ConfigFactory

@ccompatUsedUntil213
object ClusterShardingRememberEntitiesSpec {

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg =>
    msg match {
      case id: Int                     => id.toString
      case ShardRegion.StartEntity(id) => id
    }

}

abstract class ClusterShardingRememberEntitiesSpecConfig(mode: String, rememberEntities: Boolean)
    extends MultiNodeClusterShardingConfig(
      mode,
      rememberEntities,
      additionalConfig = s"""
      akka.testconductor.barrier-timeout = 60 s
      akka.test.single-expect-default = 60 s
      akka.persistence.journal.leveldb-shared.store.native = off
      """) {

  val first = role("first")
  val second = role("second")
  val third = role("third")

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

abstract class PersistentClusterShardingRememberEntitiesSpec(rememberEntities: Boolean)
    extends ClusterShardingRememberEntitiesSpec(
      new PersistentClusterShardingRememberEntitiesSpecConfig(rememberEntities))
abstract class DDataClusterShardingRememberEntitiesSpec(rememberEntities: Boolean)
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

abstract class ClusterShardingRememberEntitiesSpec(multiNodeConfig: ClusterShardingRememberEntitiesSpecConfig)
    extends MultiNodeClusterShardingSpec(multiNodeConfig)
    with ImplicitSender {
  import ClusterShardingRememberEntitiesSpec._
  import MultiNodeClusterShardingSpec.EntityActor
  import multiNodeConfig._

  val dataType = "Entity"

  def startSharding(sys: ActorSystem, probe: ActorRef): ActorRef = {
    startSharding(
      sys,
      typeName = dataType,
      entityProps = Props(new EntityActor(probe)),
      settings = ClusterShardingSettings(sys).withRememberEntities(rememberEntities),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion(dataType)

  def expectEntityRestarted(
      sys: ActorSystem,
      event: Int,
      probe: TestProbe,
      entityProbe: TestProbe): EntityActor.Started = {
    if (!rememberEntities) {
      probe.send(ClusterSharding(sys).shardRegion(dataType), event)
      probe.expectMsg(1)
    }

    entityProbe.expectMsgType[EntityActor.Started](30.seconds)
  }

  s"Cluster sharding with remember entities ($mode)" must {

    "start remembered entities when coordinator fail over" in within(30.seconds) {
      startPersistenceIfNotDdataMode(startOn = first, setStoreOn = Seq(first, second, third))

      val entityProbe = TestProbe()
      val probe = TestProbe()
      join(second, second)
      runOn(second) {
        startSharding(system, entityProbe.ref)
        probe.send(region, 1)
        probe.expectMsg(1)
        entityProbe.expectMsgType[EntityActor.Started]
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

        if (!isDdataMode) setStore(sys2, storeOn = first)

        Cluster(sys2).join(Cluster(sys2).selfAddress)

        startSharding(sys2, entityProbe2.ref)

        expectEntityRestarted(sys2, 1, probe2, entityProbe2)

        shutdown(sys2)
      }
      enterBarrier("after-3")
    }
  }
}

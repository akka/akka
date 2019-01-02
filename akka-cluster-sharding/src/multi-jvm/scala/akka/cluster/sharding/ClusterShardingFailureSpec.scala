/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.io.File

import akka.cluster.sharding.ShardRegion.Passivate

import scala.concurrent.duration._
import org.apache.commons.io.FileUtils
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.{ Cluster, MemberStatus, MultiNodeClusterSpec }
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._

object ClusterShardingFailureSpec {
  case class Get(id: String)
  case class Add(id: String, i: Int)
  case class Value(id: String, n: Int)

  class Entity extends Actor {
    var n = 0

    def receive = {
      case Get(id)   ⇒ sender() ! Value(id, n)
      case Add(_, i) ⇒ n += i
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case m @ Get(id)    ⇒ (id, m)
    case m @ Add(id, _) ⇒ (id, m)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Get(id)    ⇒ id.charAt(0).toString
    case Add(id, _) ⇒ id.charAt(0).toString
  }

}

abstract class ClusterShardingFailureSpecConfig(val mode: String) extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.roles = ["backend"]
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/ClusterShardingFailureSpec/journal"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ClusterShardingFailureSpec/snapshots"
    akka.cluster.sharding {
      coordinator-failure-backoff = 3s
      shard-failure-backoff = 3s
      state-store-mode = "$mode"
    }
    akka.cluster.sharding.distributed-data.durable.lmdb {
      dir = target/ClusterShardingFailureSpec/sharding-ddata
      map-size = 10 MiB
    }
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

object PersistentClusterShardingFailureSpecConfig extends ClusterShardingFailureSpecConfig("persistence")
object DDataClusterShardingFailureSpecConfig extends ClusterShardingFailureSpecConfig("ddata")

class PersistentClusterShardingFailureSpec extends ClusterShardingFailureSpec(PersistentClusterShardingFailureSpecConfig)
class DDataClusterShardingFailureSpec extends ClusterShardingFailureSpec(DDataClusterShardingFailureSpecConfig)

class PersistentClusterShardingFailureMultiJvmNode1 extends PersistentClusterShardingFailureSpec
class PersistentClusterShardingFailureMultiJvmNode2 extends PersistentClusterShardingFailureSpec
class PersistentClusterShardingFailureMultiJvmNode3 extends PersistentClusterShardingFailureSpec

class DDataClusterShardingFailureMultiJvmNode1 extends DDataClusterShardingFailureSpec
class DDataClusterShardingFailureMultiJvmNode2 extends DDataClusterShardingFailureSpec
class DDataClusterShardingFailureMultiJvmNode3 extends DDataClusterShardingFailureSpec

abstract class ClusterShardingFailureSpec(config: ClusterShardingFailureSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingFailureSpec._
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

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
      startSharding()

      within(remaining) {
        awaitAssert {
          cluster.state.members.map(_.uniqueAddress) should contain(cluster.selfUniqueAddress)
          cluster.state.members.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system).withRememberEntities(true),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster sharding ($mode) with flaky journal/network" must {

    if (!isDdataMode) {
      "setup shared journal" in {
        // start the Persistence extension
        Persistence(system)
        runOn(controller) {
          system.actorOf(Props[SharedLeveldbStore], "store")
        }
        enterBarrier("persistence-started")

        runOn(first, second) {
          system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
          val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
          SharedLeveldbJournal.setStore(sharedStore, system)
        }

        enterBarrier("after-1")
      }
    }

    "join cluster" in within(20.seconds) {
      join(first, first)
      join(second, first)

      runOn(first) {
        region ! Add("10", 1)
        region ! Add("20", 2)
        region ! Add("21", 3)
        region ! Get("10")
        expectMsg(Value("10", 1))
        region ! Get("20")
        expectMsg(Value("20", 2))
        region ! Get("21")
        expectMsg(Value("21", 3))
      }

      enterBarrier("after-2")
    }

    "recover after journal/network failure" in within(20.seconds) {
      runOn(controller) {
        if (isDdataMode)
          testConductor.blackhole(first, second, Direction.Both).await
        else {
          testConductor.blackhole(controller, first, Direction.Both).await
          testConductor.blackhole(controller, second, Direction.Both).await
        }
      }
      enterBarrier("journal-blackholed")

      runOn(first) {
        // try with a new shard, will not reply until journal/network is available again
        region ! Add("40", 4)
        val probe = TestProbe()
        region.tell(Get("40"), probe.ref)
        probe.expectNoMessage(1.second)
      }

      enterBarrier("first-delayed")

      runOn(controller) {
        if (isDdataMode)
          testConductor.passThrough(first, second, Direction.Both).await
        else {
          testConductor.passThrough(controller, first, Direction.Both).await
          testConductor.passThrough(controller, second, Direction.Both).await
        }
      }
      enterBarrier("journal-ok")

      runOn(first) {
        region ! Get("21")
        expectMsg(Value("21", 3))
        val entity21 = lastSender
        val shard2 = system.actorSelection(entity21.path.parent)

        //Test the ShardCoordinator allocating shards after a journal/network failure
        region ! Add("30", 3)

        //Test the Shard starting entities and persisting after a journal/network failure
        region ! Add("11", 1)

        //Test the Shard passivate works after a journal failure
        shard2.tell(Passivate(PoisonPill), entity21)

        awaitCond({
          region ! Get("21")
          expectMsgType[Value] == Value("21", 0)
        }, message = "Passivating did not reset Value down to 0")

        region ! Add("21", 1)

        region ! Get("21")
        expectMsg(Value("21", 1))

        region ! Get("30")
        expectMsg(Value("30", 3))

        region ! Get("11")
        expectMsg(Value("11", 1))

        region ! Get("40")
        expectMsg(Value("40", 4))
      }

      enterBarrier("verified-first")

      runOn(second) {
        region ! Add("10", 1)
        region ! Add("20", 2)
        region ! Add("30", 3)
        region ! Add("11", 4)
        region ! Get("10")
        expectMsg(Value("10", 2))
        region ! Get("11")
        expectMsg(Value("11", 5))
        region ! Get("20")
        expectMsg(Value("20", 4))
        region ! Get("30")
        expectMsg(Value("30", 6))
      }

      enterBarrier("after-3")

    }

  }
}


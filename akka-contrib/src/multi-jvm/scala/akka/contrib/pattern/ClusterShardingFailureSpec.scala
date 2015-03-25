/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import java.io.File
import akka.contrib.pattern.ShardRegion.Passivate

import scala.concurrent.duration._
import org.apache.commons.io.FileUtils
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._

object ClusterShardingFailureSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.roles = ["backend"]
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/journal-ClusterShardingFailureSpec"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingFailureSpec"
    akka.contrib.cluster.sharding.coordinator-failure-backoff = 3s
    akka.contrib.cluster.sharding.shard-failure-backoff = 3s
    """))

  testTransport(on = true)

  case class Get(id: String)
  case class Add(id: String, i: Int)
  case class Value(id: String, n: Int)

  class Entity extends Actor {
    var n = 0

    def receive = {
      case Get(id)    ⇒ sender() ! Value(id, n)
      case Add(id, i) ⇒ n += i
    }
  }

  val idExtractor: ShardRegion.IdExtractor = {
    case m @ Get(id)    ⇒ (id, m)
    case m @ Add(id, _) ⇒ (id, m)
  }

  val shardResolver: ShardRegion.ShardResolver = msg ⇒ msg match {
    case Get(id)    ⇒ id.charAt(0).toString
    case Add(id, _) ⇒ id.charAt(0).toString
  }

}

class ClusterShardingFailureMultiJvmNode1 extends ClusterShardingFailureSpec
class ClusterShardingFailureMultiJvmNode2 extends ClusterShardingFailureSpec
class ClusterShardingFailureMultiJvmNode3 extends ClusterShardingFailureSpec

class ClusterShardingFailureSpec extends MultiNodeSpec(ClusterShardingFailureSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingFailureSpec._

  override def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(controller) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

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
      entryProps = Some(Props[Entity]),
      roleOverride = None,
      rememberEntries = true,
      idExtractor = idExtractor,
      shardResolver = shardResolver)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  "Cluster sharding with flaky journal" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(first, second) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
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

    "recover after journal failure" in within(20.seconds) {
      runOn(controller) {
        testConductor.blackhole(controller, first, Direction.Both).await
        testConductor.blackhole(controller, second, Direction.Both).await
      }
      enterBarrier("journal-blackholed")

      runOn(first) {
        // try with a new shard, will not reply until journal is available again
        region ! Add("40", 4)
        val probe = TestProbe()
        region.tell(Get("40"), probe.ref)
        probe.expectNoMsg(1.second)
      }

      enterBarrier("first-delayed")

      runOn(controller) {
        testConductor.passThrough(controller, first, Direction.Both).await
        testConductor.passThrough(controller, second, Direction.Both).await
      }
      enterBarrier("journal-ok")

      runOn(first) {
        region ! Get("21")
        expectMsg(Value("21", 3))
        val entry21 = lastSender
        val shard2 = system.actorSelection(entry21.path.parent)

        //Test the ShardCoordinator allocating shards during a journal failure
        region ! Add("30", 3)

        //Test the Shard starting entries and persisting during a journal failure
        region ! Add("11", 1)

        //Test the Shard passivate works during a journal failure
        shard2.tell(Passivate(PoisonPill), entry21)
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


/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.sharding

import scala.collection.immutable
import java.io.File
import akka.cluster.sharding.ShardRegion.Passivate
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
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import scala.concurrent.Future
import akka.util.Timeout
import akka.pattern.ask

object ClusterShardingGracefulShutdownSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared {
      timeout = 5s
      store {
        native = off
        dir = "target/journal-ClusterShardingGracefulShutdownSpec"
      }
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/snapshots-ClusterShardingGracefulShutdownSpec"
    """))

  case object StopEntity

  class Entity extends Actor {
    def receive = {
      case id: Int ⇒ sender() ! id
      case StopEntity ⇒
        context.stop(self)
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int ⇒ (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg ⇒ msg match {
    case id: Int ⇒ id.toString
  }

  //#graceful-shutdown
  class IllustrateGracefulShutdown extends Actor {
    val system = context.system
    val cluster = Cluster(system)
    val region = ClusterSharding(system).shardRegion("Entity")

    def receive = {
      case "leave" ⇒
        context.watch(region)
        region ! ShardRegion.GracefulShutdown

      case Terminated(`region`) ⇒
        cluster.registerOnMemberRemoved(system.terminate())
        cluster.leave(cluster.selfAddress)
    }
  }
  //#graceful-shutdown

}

class ClusterShardingGracefulShutdownMultiJvmNode1 extends ClusterShardingGracefulShutdownSpec
class ClusterShardingGracefulShutdownMultiJvmNode2 extends ClusterShardingGracefulShutdownSpec

class ClusterShardingGracefulShutdownSpec extends MultiNodeSpec(ClusterShardingGracefulShutdownSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingGracefulShutdownSpec._

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
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    val allocationStrategy = new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId,
      allocationStrategy,
      handOffStopMessage = StopEntity)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  "Cluster sharding" must {

    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(first) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(first, second) {
        system.actorSelection(node(first) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "start some shards in both regions" in within(30.seconds) {
      join(first, first)
      join(second, first)

      awaitAssert {
        val p = TestProbe()
        val regionAddresses = (1 to 100).map { n ⇒
          region.tell(n, p.ref)
          p.expectMsg(1.second, n)
          p.lastSender.path.address
        }.toSet
        regionAddresses.size should be(2)
      }
      enterBarrier("after-2")
    }

    "gracefully shutdown a region" in within(30.seconds) {
      runOn(second) {
        region ! ShardRegion.GracefulShutdown
      }

      runOn(first) {
        awaitAssert {
          val p = TestProbe()
          for (n ← 1 to 200) {
            region.tell(n, p.ref)
            p.expectMsg(1.second, n)
            p.lastSender.path should be(region.path / n.toString / n.toString)
          }
        }
      }
      enterBarrier("handoff-completed")

      runOn(second) {
        watch(region)
        expectTerminated(region)
      }

      enterBarrier("after-3")
    }

  }
}


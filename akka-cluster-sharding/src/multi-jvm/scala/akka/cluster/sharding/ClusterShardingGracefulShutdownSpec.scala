/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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

object ClusterShardingGracefulShutdownSpec {
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
        cluster.registerOnMemberRemoved(self ! "member-removed")
        cluster.leave(cluster.selfAddress)

      case "member-removed" ⇒
        // Let singletons hand over gracefully before stopping the system
        import context.dispatcher
        system.scheduler.scheduleOnce(10.seconds, self, "stop-system")

      case "stop-system" ⇒
        system.terminate()
    }
  }
  //#graceful-shutdown

}

abstract class ClusterShardingGracefulShutdownSpecConfig(val mode: String) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(ConfigFactory.parseString(s"""
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
    akka.cluster.sharding.state-store-mode = "$mode"
    """))
}

object PersistentClusterShardingGracefulShutdownSpecConfig extends ClusterShardingGracefulShutdownSpecConfig("persistence")
object DDataClusterShardingGracefulShutdownSpecConfig extends ClusterShardingGracefulShutdownSpecConfig("ddata")

class PersistentClusterShardingGracefulShutdownSpec extends ClusterShardingGracefulShutdownSpec(PersistentClusterShardingGracefulShutdownSpecConfig)
class DDataClusterShardingGracefulShutdownSpec extends ClusterShardingGracefulShutdownSpec(DDataClusterShardingGracefulShutdownSpecConfig)

class PersistentClusterShardingGracefulShutdownMultiJvmNode1 extends PersistentClusterShardingGracefulShutdownSpec
class PersistentClusterShardingGracefulShutdownMultiJvmNode2 extends PersistentClusterShardingGracefulShutdownSpec

class DDataClusterShardingGracefulShutdownMultiJvmNode1 extends DDataClusterShardingGracefulShutdownSpec
class DDataClusterShardingGracefulShutdownMultiJvmNode2 extends DDataClusterShardingGracefulShutdownSpec

abstract class ClusterShardingGracefulShutdownSpec(config: ClusterShardingGracefulShutdownSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingGracefulShutdownSpec._
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

  s"Cluster sharding ($mode)" must {

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

    "gracefully shutdown empty region" in within(30.seconds) {
      runOn(first) {
        val allocationStrategy = new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
        val regionEmpty = ClusterSharding(system).start(
          typeName = "EntityEmpty",
          entityProps = Props[Entity],
          settings = ClusterShardingSettings(system),
          extractEntityId = extractEntityId,
          extractShardId = extractShardId,
          allocationStrategy,
          handOffStopMessage = StopEntity)

        watch(regionEmpty)
        regionEmpty ! GracefulShutdown
        expectTerminated(regionEmpty, 5.seconds)
      }
    }

  }
}


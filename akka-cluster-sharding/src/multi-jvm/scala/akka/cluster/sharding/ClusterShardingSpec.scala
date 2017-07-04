/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import akka.cluster.ddata.{ ReplicatorSettings, Replicator }
import akka.cluster.sharding.ShardCoordinator.Internal.{ ShardStopped, HandOff }
import akka.cluster.sharding.ShardRegion.Passivate
import akka.cluster.sharding.ShardRegion.GetCurrentRegions
import akka.cluster.sharding.ShardRegion.CurrentRegions
import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.cluster.Cluster
import akka.persistence.PersistentActor
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent.Mute
import java.io.File
import org.apache.commons.io.FileUtils
import akka.cluster.singleton.ClusterSingletonManager
import akka.cluster.singleton.ClusterSingletonManagerSettings
import akka.pattern.BackoffSupervisor

object ClusterShardingSpec {
  //#counter-actor
  case object Increment
  case object Decrement
  final case class Get(counterId: Long)
  final case class EntityEnvelope(id: Long, payload: Any)

  case object Stop
  final case class CounterChanged(delta: Int)

  class Counter extends PersistentActor {
    import ShardRegion.Passivate

    context.setReceiveTimeout(120.seconds)

    // self.path.name is the entity identifier (utf-8 URL-encoded)
    override def persistenceId: String = "Counter-" + self.path.name

    var count = 0
    //#counter-actor

    override def postStop(): Unit = {
      super.postStop()
      // Simulate that the passivation takes some time, to verify passivation buffering
      Thread.sleep(500)
    }
    //#counter-actor

    def updateState(event: CounterChanged): Unit =
      count += event.delta

    override def receiveRecover: Receive = {
      case evt: CounterChanged ⇒ updateState(evt)
    }

    override def receiveCommand: Receive = {
      case Increment      ⇒ persist(CounterChanged(+1))(updateState)
      case Decrement      ⇒ persist(CounterChanged(-1))(updateState)
      case Get(_)         ⇒ sender() ! count
      case ReceiveTimeout ⇒ context.parent ! Passivate(stopMessage = Stop)
      case Stop           ⇒ context.stop(self)
    }
  }
  //#counter-actor

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) ⇒ (id.toString, payload)
    case msg @ Get(id)               ⇒ (id.toString, msg)
  }

  val numberOfShards = 12

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _)       ⇒ (id % numberOfShards).toString
    case Get(id)                     ⇒ (id % numberOfShards).toString
    case ShardRegion.StartEntity(id) ⇒ (id.toLong % numberOfShards).toString
  }

  def qualifiedCounterProps(typeName: String): Props =
    Props(new QualifiedCounter(typeName))

  class QualifiedCounter(typeName: String) extends Counter {
    override def persistenceId: String = typeName + "-" + self.path.name
  }

  class AnotherCounter extends QualifiedCounter("AnotherCounter")

  //#supervisor
  class CounterSupervisor extends Actor {
    val counter = context.actorOf(Props[Counter], "theCounter")

    override val supervisorStrategy = OneForOneStrategy() {
      case _: IllegalArgumentException     ⇒ SupervisorStrategy.Resume
      case _: ActorInitializationException ⇒ SupervisorStrategy.Stop
      case _: DeathPactException           ⇒ SupervisorStrategy.Stop
      case _: Exception                    ⇒ SupervisorStrategy.Restart
    }

    def receive = {
      case msg ⇒ counter forward msg
    }
  }
  //#supervisor

}

abstract class ClusterShardingSpecConfig(
  val mode:                   String,
  val entityRecoveryStrategy: String = "all")
  extends MultiNodeConfig {

  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
  val sixth = role("sixth")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    akka.cluster.roles = ["backend"]
    akka.cluster.distributed-data.gossip-interval = 1s
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/ClusterShardingSpec/journal"
    }
    akka.persistence.snapshot-store.plugin = "akka.persistence.snapshot-store.local"
    akka.persistence.snapshot-store.local.dir = "target/ClusterShardingSpec/snapshots"
    akka.cluster.sharding {
      retry-interval = 1 s
      handoff-timeout = 10 s
      shard-start-timeout = 5s
      entity-restart-backoff = 1s
      rebalance-interval = 2 s
      state-store-mode = "$mode"
      entity-recovery-strategy = "$entityRecoveryStrategy"
      entity-recovery-constant-rate-strategy {
        frequency = 1 ms
        number-of-entities = 1
      }
      least-shard-allocation-strategy {
        rebalance-threshold = 2
        max-simultaneous-rebalance = 1
      }
      distributed-data.durable.lmdb {
        dir = target/ClusterShardingSpec/sharding-ddata
        map-size = 10 MiB
      }
    }
    akka.testconductor.barrier-timeout = 70s
    """))
  nodeConfig(sixth) {
    ConfigFactory.parseString("""akka.cluster.roles = ["frontend"]""")
  }
}

// only used in documentation
object ClusterShardingDocCode {
  import ClusterShardingSpec._

  //#counter-extractor
  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) ⇒ (id.toString, payload)
    case msg @ Get(id)               ⇒ (id.toString, msg)
  }

  val numberOfShards = 100

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) ⇒ (id % numberOfShards).toString
    case Get(id)               ⇒ (id % numberOfShards).toString
    case ShardRegion.StartEntity(id) ⇒
      // StartEntity is used by remembering entities feature
      (id.toLong % numberOfShards).toString
  }
  //#counter-extractor

  {
    //#extractShardId-StartEntity
    val extractShardId: ShardRegion.ExtractShardId = {
      case EntityEnvelope(id, _) ⇒ (id % numberOfShards).toString
      case Get(id)               ⇒ (id % numberOfShards).toString
      case ShardRegion.StartEntity(id) ⇒
        // StartEntity is used by remembering entities feature
        (id.toLong % numberOfShards).toString
    }
    //#extractShardId-StartEntity
  }

}

object PersistentClusterShardingSpecConfig extends ClusterShardingSpecConfig("persistence")
object DDataClusterShardingSpecConfig extends ClusterShardingSpecConfig("ddata")
object PersistentClusterShardingWithEntityRecoverySpecConfig extends ClusterShardingSpecConfig(
  "persistence",
  "all")
object DDataClusterShardingWithEntityRecoverySpecConfig extends ClusterShardingSpecConfig(
  "ddata",
  "constant")

class PersistentClusterShardingSpec extends ClusterShardingSpec(PersistentClusterShardingSpecConfig)
class DDataClusterShardingSpec extends ClusterShardingSpec(DDataClusterShardingSpecConfig)
class PersistentClusterShardingWithEntityRecoverySpec extends ClusterShardingSpec(PersistentClusterShardingWithEntityRecoverySpecConfig)
class DDataClusterShardingWithEntityRecoverySpec extends ClusterShardingSpec(DDataClusterShardingWithEntityRecoverySpecConfig)

class PersistentClusterShardingMultiJvmNode1 extends PersistentClusterShardingSpec
class PersistentClusterShardingMultiJvmNode2 extends PersistentClusterShardingSpec
class PersistentClusterShardingMultiJvmNode3 extends PersistentClusterShardingSpec
class PersistentClusterShardingMultiJvmNode4 extends PersistentClusterShardingSpec
class PersistentClusterShardingMultiJvmNode5 extends PersistentClusterShardingSpec
class PersistentClusterShardingMultiJvmNode6 extends PersistentClusterShardingSpec
class PersistentClusterShardingMultiJvmNode7 extends PersistentClusterShardingSpec

class DDataClusterShardingMultiJvmNode1 extends DDataClusterShardingSpec
class DDataClusterShardingMultiJvmNode2 extends DDataClusterShardingSpec
class DDataClusterShardingMultiJvmNode3 extends DDataClusterShardingSpec
class DDataClusterShardingMultiJvmNode4 extends DDataClusterShardingSpec
class DDataClusterShardingMultiJvmNode5 extends DDataClusterShardingSpec
class DDataClusterShardingMultiJvmNode6 extends DDataClusterShardingSpec
class DDataClusterShardingMultiJvmNode7 extends DDataClusterShardingSpec

class PersistentClusterShardingWithEntityRecoveryMultiJvmNode1 extends PersistentClusterShardingSpec
class PersistentClusterShardingWithEntityRecoveryMultiJvmNode2 extends PersistentClusterShardingSpec
class PersistentClusterShardingWithEntityRecoveryMultiJvmNode3 extends PersistentClusterShardingSpec
class PersistentClusterShardingWithEntityRecoveryMultiJvmNode4 extends PersistentClusterShardingSpec
class PersistentClusterShardingWithEntityRecoveryMultiJvmNode5 extends PersistentClusterShardingSpec
class PersistentClusterShardingWithEntityRecoveryMultiJvmNode6 extends PersistentClusterShardingSpec
class PersistentClusterShardingWithEntityRecoveryMultiJvmNode7 extends PersistentClusterShardingSpec

class DDataClusterShardingWithEntityRecoveryMultiJvmNode1 extends DDataClusterShardingSpec
class DDataClusterShardingWithEntityRecoveryMultiJvmNode2 extends DDataClusterShardingSpec
class DDataClusterShardingWithEntityRecoveryMultiJvmNode3 extends DDataClusterShardingSpec
class DDataClusterShardingWithEntityRecoveryMultiJvmNode4 extends DDataClusterShardingSpec
class DDataClusterShardingWithEntityRecoveryMultiJvmNode5 extends DDataClusterShardingSpec
class DDataClusterShardingWithEntityRecoveryMultiJvmNode6 extends DDataClusterShardingSpec
class DDataClusterShardingWithEntityRecoveryMultiJvmNode7 extends DDataClusterShardingSpec

abstract class ClusterShardingSpec(config: ClusterShardingSpecConfig) extends MultiNodeSpec(config) with STMultiNodeSpec with ImplicitSender {
  import ClusterShardingSpec._
  import config._

  override def initialParticipants = roles.size

  val storageLocations = List(new File(system.settings.config.getString(
    "akka.cluster.sharding.distributed-data.durable.lmdb.dir")).getParentFile)

  override protected def atStartup() {
    storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteQuietly(dir))
    enterBarrier("startup")
  }

  override protected def afterTermination() {
    storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteQuietly(dir))
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createCoordinator()
    }
    enterBarrier(from.name + "-joined")
  }

  lazy val replicator = system.actorOf(Replicator.props(
    ReplicatorSettings(system).withGossipInterval(1.second).withMaxDeltaElements(10)), "replicator")

  def createCoordinator(): Unit = {

    def coordinatorProps(typeName: String, rebalanceEnabled: Boolean, rememberEntities: Boolean) = {
      val allocationStrategy = new ShardCoordinator.LeastShardAllocationStrategy(rebalanceThreshold = 2, maxSimultaneousRebalance = 1)
      val cfg = ConfigFactory.parseString(s"""
      handoff-timeout = 10s
      shard-start-timeout = 10s
      rebalance-interval = ${if (rebalanceEnabled) "2s" else "3600s"}
      """).withFallback(system.settings.config.getConfig("akka.cluster.sharding"))
      val settings = ClusterShardingSettings(cfg).withRememberEntities(rememberEntities)
      val majorityMinCap = system.settings.config.getInt(
        "akka.cluster.sharding.distributed-data.majority-min-cap")
      if (settings.stateStoreMode == "persistence")
        ShardCoordinator.props(typeName, settings, allocationStrategy)
      else
        ShardCoordinator.props(typeName, settings, allocationStrategy, replicator, majorityMinCap)
    }

    List("counter", "rebalancingCounter", "RememberCounterEntities", "AnotherRememberCounter",
      "RememberCounter", "RebalancingRememberCounter", "AutoMigrateRememberRegionTest").foreach { typeName ⇒
        val rebalanceEnabled = typeName.toLowerCase.startsWith("rebalancing")
        val rememberEnabled = typeName.toLowerCase.contains("remember")
        val singletonProps = BackoffSupervisor.props(
          childProps = coordinatorProps(typeName, rebalanceEnabled, rememberEnabled),
          childName = "coordinator",
          minBackoff = 5.seconds,
          maxBackoff = 5.seconds,
          randomFactor = 0.1).withDeploy(Deploy.local)
        system.actorOf(
          ClusterSingletonManager.props(
            singletonProps,
            terminationMessage = PoisonPill,
            settings = ClusterSingletonManagerSettings(system)),
          name = typeName + "Coordinator")
      }
  }

  def createRegion(typeName: String, rememberEntities: Boolean): ActorRef = {
    val cfg = ConfigFactory.parseString("""
      retry-interval = 1s
      shard-failure-backoff = 1s
      entity-restart-backoff = 1s
      buffer-size = 1000
      """).withFallback(system.settings.config.getConfig("akka.cluster.sharding"))
    val settings = ClusterShardingSettings(cfg)
      .withRememberEntities(rememberEntities)
    system.actorOf(
      ShardRegion.props(
        typeName = typeName,
        entityProps = qualifiedCounterProps(typeName),
        settings = settings,
        coordinatorPath = "/user/" + typeName + "Coordinator/singleton/coordinator",
        extractEntityId = extractEntityId,
        extractShardId = extractShardId,
        handOffStopMessage = PoisonPill,
        replicator,
        majorityMinCap = 3),
      name = typeName + "Region")
  }

  lazy val region = createRegion("counter", rememberEntities = false)
  lazy val rebalancingRegion = createRegion("rebalancingCounter", rememberEntities = false)

  lazy val persistentEntitiesRegion = createRegion("RememberCounterEntities", rememberEntities = true)
  lazy val anotherPersistentRegion = createRegion("AnotherRememberCounter", rememberEntities = true)
  lazy val persistentRegion = createRegion("RememberCounter", rememberEntities = true)
  lazy val rebalancingPersistentRegion = createRegion("RebalancingRememberCounter", rememberEntities = true)
  lazy val autoMigrateRegion = createRegion("AutoMigrateRememberRegionTest", rememberEntities = true)

  def isDdataMode: Boolean = mode == ClusterShardingSettings.StateStoreModeDData

  s"Cluster sharding ($mode)" must {

    // must be done also in ddata mode since Counter is PersistentActor
    "setup shared journal" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(first, second, third, fourth, fifth, sixth) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity](10.seconds).ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "work in single node cluster" in within(20 seconds) {
      join(first, first)

      runOn(first) {
        region ! EntityEnvelope(1, Increment)
        region ! EntityEnvelope(1, Increment)
        region ! EntityEnvelope(1, Increment)
        region ! EntityEnvelope(1, Decrement)
        region ! Get(1)
        expectMsg(2)

        region ! GetCurrentRegions
        expectMsg(CurrentRegions(Set(Cluster(system).selfAddress)))
      }

      enterBarrier("after-2")
    }

    "use second node" in within(20 seconds) {
      join(second, first)

      runOn(second) {
        region ! EntityEnvelope(2, Increment)
        region ! EntityEnvelope(2, Increment)
        region ! EntityEnvelope(2, Increment)
        region ! EntityEnvelope(2, Decrement)
        region ! Get(2)
        expectMsg(2)

        region ! EntityEnvelope(11, Increment)
        region ! EntityEnvelope(12, Increment)
        region ! Get(11)
        expectMsg(1)
        region ! Get(12)
        expectMsg(1)
      }
      enterBarrier("second-update")
      runOn(first) {
        region ! EntityEnvelope(2, Increment)
        region ! Get(2)
        expectMsg(3)
        lastSender.path should ===(node(second) / "user" / "counterRegion" / "2" / "2")

        region ! Get(11)
        expectMsg(1)
        // local on first
        lastSender.path should ===(region.path / "11" / "11")
        region ! Get(12)
        expectMsg(1)
        lastSender.path should ===(node(second) / "user" / "counterRegion" / "0" / "12")
      }
      enterBarrier("first-update")

      runOn(second) {
        region ! Get(2)
        expectMsg(3)
        lastSender.path should ===(region.path / "2" / "2")

        region ! GetCurrentRegions
        expectMsg(CurrentRegions(Set(Cluster(system).selfAddress, node(first).address)))
      }

      enterBarrier("after-3")
    }

    "support passivation and activation of entities" in {
      runOn(second) {
        region ! Get(2)
        expectMsg(3)
        region ! EntityEnvelope(2, ReceiveTimeout)
        // let the Passivate-Stop roundtrip begin to trigger buffering of subsequent messages
        Thread.sleep(200)
        region ! EntityEnvelope(2, Increment)
        region ! Get(2)
        expectMsg(4)
      }
      enterBarrier("after-4")
    }

    "support proxy only mode" in within(10.seconds) {
      runOn(second) {
        val cfg = ConfigFactory.parseString("""
          retry-interval = 1s
          buffer-size = 1000
        """).withFallback(system.settings.config.getConfig("akka.cluster.sharding"))
        val settings = ClusterShardingSettings(cfg)
        val proxy = system.actorOf(
          ShardRegion.proxyProps(
            typeName = "counter",
            team = None,
            settings,
            coordinatorPath = "/user/counterCoordinator/singleton/coordinator",
            extractEntityId = extractEntityId,
            extractShardId = extractShardId,
            system.deadLetters,
            majorityMinCap = 0),
          name = "regionProxy")

        proxy ! Get(1)
        expectMsg(2)
        proxy ! Get(2)
        expectMsg(4)
      }
      enterBarrier("after-5")
    }

    "failover shards on crashed node" in within(30 seconds) {
      // mute logging of deadLetters during shutdown of systems
      if (!log.isDebugEnabled)
        system.eventStream.publish(Mute(DeadLettersFilter[Any]))
      enterBarrier("logs-muted")

      runOn(controller) {
        testConductor.exit(second, 0).await
      }
      enterBarrier("crash-second")

      runOn(first) {
        val probe1 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(2), probe1.ref)
            probe1.expectMsg(4)
            probe1.lastSender.path should ===(region.path / "2" / "2")
          }
        }
        val probe2 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(12), probe2.ref)
            probe2.expectMsg(1)
            probe2.lastSender.path should ===(region.path / "0" / "12")
          }
        }
      }

      enterBarrier("after-6")
    }

    "use third and fourth node" in within(15 seconds) {
      join(third, first)

      runOn(third) {
        for (_ ← 1 to 10)
          region ! EntityEnvelope(3, Increment)
        region ! Get(3)
        expectMsg(10)
        lastSender.path should ===(region.path / "3" / "3") // local
      }
      enterBarrier("third-update")

      join(fourth, first)

      runOn(fourth) {
        for (_ ← 1 to 20)
          region ! EntityEnvelope(4, Increment)
        region ! Get(4)
        expectMsg(20)
        lastSender.path should ===(region.path / "4" / "4") // local
      }
      enterBarrier("fourth-update")

      runOn(first) {
        region ! EntityEnvelope(3, Increment)
        region ! Get(3)
        expectMsg(11)
        lastSender.path should ===(node(third) / "user" / "counterRegion" / "3" / "3")

        region ! EntityEnvelope(4, Increment)
        region ! Get(4)
        expectMsg(21)
        lastSender.path should ===(node(fourth) / "user" / "counterRegion" / "4" / "4")
      }
      enterBarrier("first-update")

      runOn(third) {
        region ! Get(3)
        expectMsg(11)
        lastSender.path should ===(region.path / "3" / "3")
      }

      runOn(fourth) {
        region ! Get(4)
        expectMsg(21)
        lastSender.path should ===(region.path / "4" / "4")
      }

      enterBarrier("after-7")
    }

    "recover coordinator state after coordinator crash" in within(60 seconds) {
      join(fifth, fourth)

      runOn(controller) {
        testConductor.exit(first, 0).await
      }
      enterBarrier("crash-first")

      runOn(fifth) {
        val probe3 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(3), probe3.ref)
            probe3.expectMsg(11)
            probe3.lastSender.path should ===(node(third) / "user" / "counterRegion" / "3" / "3")
          }
        }
        val probe4 = TestProbe()
        awaitAssert {
          within(1.second) {
            region.tell(Get(4), probe4.ref)
            probe4.expectMsg(21)
            probe4.lastSender.path should ===(node(fourth) / "user" / "counterRegion" / "4" / "4")
          }
        }

      }

      enterBarrier("after-8")
    }

    "rebalance to nodes with less shards" in within(60 seconds) {
      runOn(fourth) {
        for (n ← 1 to 10) {
          rebalancingRegion ! EntityEnvelope(n, Increment)
          rebalancingRegion ! Get(n)
          expectMsg(1)
        }
      }
      enterBarrier("rebalancing-shards-allocated")

      join(sixth, third)

      runOn(sixth) {
        awaitAssert {
          val probe = TestProbe()
          within(3.seconds) {
            var count = 0
            for (n ← 1 to 10) {
              rebalancingRegion.tell(Get(n), probe.ref)
              probe.expectMsgType[Int]
              if (probe.lastSender.path == rebalancingRegion.path / (n % 12).toString / n.toString)
                count += 1
            }
            count should be >= (2)
          }
        }
      }

      enterBarrier("after-9")
    }
  }

  "easy to use with extensions" in within(50.seconds) {
    runOn(third, fourth, fifth, sixth) {
      //#counter-start
      val counterRegion: ActorRef = ClusterSharding(system).start(
        typeName = "Counter",
        entityProps = Props[Counter],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
      //#counter-start
      ClusterSharding(system).start(
        typeName = "AnotherCounter",
        entityProps = Props[AnotherCounter],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)

      //#counter-supervisor-start
      ClusterSharding(system).start(
        typeName = "SupervisedCounter",
        entityProps = Props[CounterSupervisor],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
      //#counter-supervisor-start
    }
    enterBarrier("extension-started")
    runOn(fifth) {
      //#counter-usage
      val counterRegion: ActorRef = ClusterSharding(system).shardRegion("Counter")
      counterRegion ! Get(123)
      expectMsg(0)

      counterRegion ! EntityEnvelope(123, Increment)
      counterRegion ! Get(123)
      expectMsg(1)
      //#counter-usage

      ClusterSharding(system).shardRegion("AnotherCounter") ! EntityEnvelope(123, Decrement)
      ClusterSharding(system).shardRegion("AnotherCounter") ! Get(123)
      expectMsg(-1)
    }

    enterBarrier("extension-used")

    // sixth is a frontend node, i.e. proxy only
    runOn(sixth) {
      for (n ← 1000 to 1010) {
        ClusterSharding(system).shardRegion("Counter") ! EntityEnvelope(n, Increment)
        ClusterSharding(system).shardRegion("Counter") ! Get(n)
        expectMsg(1)
        lastSender.path.address should not be (Cluster(system).selfAddress)
      }
    }

    enterBarrier("after-10")

  }
  "easy API for starting" in within(50.seconds) {
    runOn(first) {
      val counterRegionViaStart: ActorRef = ClusterSharding(system).start(
        typeName = "ApiTest",
        entityProps = Props[Counter],
        settings = ClusterShardingSettings(system),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)

      val counterRegionViaGet: ActorRef = ClusterSharding(system).shardRegion("ApiTest")

      counterRegionViaStart should equal(counterRegionViaGet)
    }
    enterBarrier("after-11")

  }

  "Persistent Cluster Shards" must {
    "recover entities upon restart" in within(50.seconds) {
      runOn(third, fourth, fifth) {
        persistentEntitiesRegion
        anotherPersistentRegion
      }
      enterBarrier("persistent-started")

      runOn(third) {
        //Create an increment counter 1
        persistentEntitiesRegion ! EntityEnvelope(1, Increment)
        persistentEntitiesRegion ! Get(1)
        expectMsg(1)

        //Shut down the shard and confirm it's dead
        val shard = system.actorSelection(lastSender.path.parent)
        val region = system.actorSelection(lastSender.path.parent.parent)

        //Stop the shard cleanly
        region ! HandOff("1")
        expectMsg(10 seconds, "ShardStopped not received", ShardStopped("1"))

        val probe = TestProbe()
        awaitAssert({
          shard.tell(Identify(1), probe.ref)
          probe.expectMsg(1 second, "Shard was still around", ActorIdentity(1, None))
        }, 5 seconds, 500 millis)

        //Get the path to where the shard now resides
        persistentEntitiesRegion ! Get(13)
        expectMsg(0)

        //Check that counter 1 is now alive again, even though we have
        // not sent a message to it via the ShardRegion
        val counter1 = system.actorSelection(lastSender.path.parent / "1")
        within(5.seconds) {
          awaitAssert {
            val p = TestProbe()
            counter1.tell(Identify(2), p.ref)
            p.expectMsgType[ActorIdentity](2.seconds).ref should not be (None)
          }
        }

        counter1 ! Get(1)
        expectMsg(1)
      }

      enterBarrier("after-shard-restart")

      runOn(fourth) {
        //Check a second region does not share the same persistent shards

        //Create a separate 13 counter
        anotherPersistentRegion ! EntityEnvelope(13, Increment)
        anotherPersistentRegion ! Get(13)
        expectMsg(1)

        //Check that no counter "1" exists in this shard
        val secondCounter1 = system.actorSelection(lastSender.path.parent / "1")
        secondCounter1 ! Identify(3)
        expectMsg(3 seconds, ActorIdentity(3, None))

      }
      enterBarrier("after-12")
    }

    "permanently stop entities which passivate" in within(15.seconds) {
      runOn(third, fourth, fifth) {
        persistentRegion
      }
      enterBarrier("cluster-started-12")

      runOn(third) {
        //Create and increment counter 1
        persistentRegion ! EntityEnvelope(1, Increment)
        persistentRegion ! Get(1)
        expectMsg(1)

        val counter1 = lastSender
        val shard = system.actorSelection(counter1.path.parent)
        val region = system.actorSelection(counter1.path.parent.parent)

        //Create and increment counter 13
        persistentRegion ! EntityEnvelope(13, Increment)
        persistentRegion ! Get(13)
        expectMsg(1)

        val counter13 = lastSender

        counter1.path.parent should ===(counter13.path.parent)

        //Send the shard the passivate message from the counter
        watch(counter1)
        shard.tell(Passivate(Stop), counter1)

        //Watch for the terminated message
        expectTerminated(counter1, 5 seconds)

        val probe1 = TestProbe()
        awaitAssert({
          //Check counter 1 is dead
          counter1.tell(Identify(1), probe1.ref)
          probe1.expectMsg(1 second, "Entity 1 was still around", ActorIdentity(1, None))
        }, 5 second, 500 millis)

        //Stop the shard cleanly
        region ! HandOff("1")
        expectMsg(10 seconds, "ShardStopped not received", ShardStopped("1"))

        val probe2 = TestProbe()
        awaitAssert({
          shard.tell(Identify(2), probe2.ref)
          probe2.expectMsg(1 second, "Shard was still around", ActorIdentity(2, None))
        }, 5 seconds, 500 millis)
      }

      enterBarrier("shard-shutdown-12")

      runOn(fourth) {
        //Force the shard back up
        persistentRegion ! Get(25)
        expectMsg(0)

        val shard = lastSender.path.parent

        //Check counter 1 is still dead
        system.actorSelection(shard / "1") ! Identify(3)
        expectMsg(ActorIdentity(3, None))

        //Check counter 13 is alive again
        val probe3 = TestProbe()
        awaitAssert({
          system.actorSelection(shard / "13").tell(Identify(4), probe3.ref)
          probe3.expectMsgType[ActorIdentity](1 second).ref should not be (None)
        }, 5 seconds, 500 millis)
      }

      enterBarrier("after-13")
    }

    "restart entities which stop without passivating" in within(50.seconds) {
      runOn(third, fourth) {
        persistentRegion
      }
      enterBarrier("cluster-started-12")

      runOn(third) {
        //Create and increment counter 1
        persistentRegion ! EntityEnvelope(1, Increment)
        persistentRegion ! Get(1)
        expectMsg(2)

        val counter1 = system.actorSelection(lastSender.path)

        counter1 ! Stop

        val probe = TestProbe()
        awaitAssert({
          counter1.tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity](1 second).ref should not be (None)
        }, 5.seconds, 500.millis)
      }

      enterBarrier("after-14")
    }

    "be migrated to new regions upon region failure" in within(15.seconds) {

      //Start only one region, and force an entity onto that region
      runOn(third) {
        autoMigrateRegion ! EntityEnvelope(1, Increment)
        autoMigrateRegion ! Get(1)
        expectMsg(1)
      }
      enterBarrier("shard1-region3")

      //Start another region and test it talks to node 3
      runOn(fourth) {
        autoMigrateRegion ! EntityEnvelope(1, Increment)

        autoMigrateRegion ! Get(1)
        expectMsg(2)
        lastSender.path should ===(node(third) / "user" / "AutoMigrateRememberRegionTestRegion" / "1" / "1")

        //Kill region 3
        system.actorSelection(lastSender.path.parent.parent) ! PoisonPill
      }
      enterBarrier("region4-up")

      // Wait for migration to happen
      //Test the shard, thus counter was moved onto node 4 and started.
      runOn(fourth) {
        val counter1 = system.actorSelection(system / "AutoMigrateRememberRegionTestRegion" / "1" / "1")
        val probe = TestProbe()
        awaitAssert({
          counter1.tell(Identify(1), probe.ref)
          probe.expectMsgType[ActorIdentity](1 second).ref should not be (None)
        }, 5.seconds, 500 millis)

        counter1 ! Get(1)
        expectMsg(2)
      }

      enterBarrier("after-15")
    }

    "ensure rebalance restarts shards" in within(50.seconds) {
      runOn(fourth) {
        for (i ← 2 to 12) {
          rebalancingPersistentRegion ! EntityEnvelope(i, Increment)
        }

        for (i ← 2 to 12) {
          rebalancingPersistentRegion ! Get(i)
          expectMsg(1)
        }
      }
      enterBarrier("entities-started")

      runOn(fifth) {
        rebalancingPersistentRegion
      }
      enterBarrier("fifth-joined-shard")

      runOn(fifth) {
        awaitAssert {
          var count = 0
          for (n ← 2 to 12) {
            val entity = system.actorSelection(rebalancingPersistentRegion.path / (n % 12).toString / n.toString)
            entity ! Identify(n)
            receiveOne(3 seconds) match {
              case ActorIdentity(id, Some(_)) if id == n ⇒ count = count + 1
              case ActorIdentity(id, None)               ⇒ //Not on the fifth shard
            }
          }
          count should be >= (2)
        }
      }

      enterBarrier("after-16")
    }
  }
}


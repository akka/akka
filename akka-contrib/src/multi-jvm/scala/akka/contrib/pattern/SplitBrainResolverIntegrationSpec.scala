/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.pattern

import java.io.File
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.PoisonPill
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.pattern.ask
import akka.pattern.pipe
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.RemoteActorRefProvider
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.{ Direction, SetThrottle, Blackhole }
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit.ImplicitSender
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.scalatest.BeforeAndAfterEach
import akka.cluster.Member

object SplitBrainResolverIntegrationSpec extends MultiNodeConfig {
  val node1 = role("node1")
  val node2 = role("node2")
  val node3 = role("node3")
  val node4 = role("node4")
  val node5 = role("node5")
  val node6 = role("node6")
  val node7 = role("node7")
  val node8 = role("node8")
  val node9 = role("node9")

  commonConfig(ConfigFactory.parseString("""
    akka {
      loglevel = INFO
      cluster {
        auto-down = off
        split-brain-resolver.stable-after = 10s
      }

      persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
      persistence.journal.leveldb-shared.store {
        native = off
        dir = "target/journal-SplitBrainResolverIntegrationSpec"
      }
      persistence.snapshot-store.local.dir = "target/snapshots-SplitBrainResolverIntegrationSpec"

      actor.provider = "akka.cluster.ClusterActorRefProvider"
      remote.log-remote-lifecycle-events = off
    }
    """))

  testTransport(on = true)

  case class Register(key: String, address: Address)
  case class Unregister(key: String, address: Address)
  case class DoubleRegister(key: String, msg: String)

  class GlobalRegistry(probe: ActorRef, onlyErrors: Boolean) extends Actor with ActorLogging {

    var registry = Map.empty[String, Address]
    var unregisterTimestamp = Map.empty[String, Long]

    override def receive = {
      case r @ Register(key, address) ⇒
        log.info("{}", r)
        if (registry.contains(key))
          probe ! DoubleRegister(key, s"trying to register $address, but ${registry(key)} was already registered for $key")
        else {
          unregisterTimestamp.get(key).foreach { t ⇒
            log.info("Unregister/register margin for [{}] was [{}] ms", key, (System.nanoTime() - t).nanos.toMillis)
          }
          registry += key -> address
          if (!onlyErrors) probe ! r
        }

      case u @ Unregister(key, address) ⇒
        log.info("{}", u)
        if (!registry.contains(key))
          probe ! s"$key was not registered"
        else if (registry(key) != address)
          probe ! s"${registry(key)} instead of $address was registered for $key"
        else {
          registry -= key
          unregisterTimestamp += key -> System.nanoTime()
          if (!onlyErrors) probe ! u
        }
    }

  }

  val idExtractor: ShardRegion.IdExtractor = {
    case id: Int ⇒ (id.toString, id)
  }

  val shardResolver: ShardRegion.ShardResolver = msg ⇒ msg match {
    case id: Int ⇒ (id % 10).toString
  }

  class SingletonActor(registry: ActorRef) extends Actor with ActorLogging {
    val key = self.path.toStringWithoutAddress

    override def preStart(): Unit = {
      log.info("Starting")
      registry ! Register(key, Cluster(context.system).selfAddress)
    }

    override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
      // don't call postStop
    }

    override def postStop(): Unit = {
      log.info("Stopping")
      registry ! Unregister(key, Cluster(context.system).selfAddress)
    }

    override def receive = {
      case i: Int ⇒ sender() ! i
    }
  }

  case class BlackholeNode(target: Address)
  case object GetAddress

  class GremlinController extends Actor with ActorLogging {
    import context.dispatcher
    val transport = context.system.asInstanceOf[ExtendedActorSystem].provider.asInstanceOf[RemoteActorRefProvider].transport
    val selfAddress = Cluster(context.system).selfAddress

    override def receive = {
      case GetAddress ⇒
        sender() ! selfAddress
      case BlackholeNode(target) ⇒
        log.debug("Blackhole {} <-> {}", selfAddress, target)
        transport.managementCommand(SetThrottle(target, Direction.Both, Blackhole)).pipeTo(sender())
    }
  }

  class GremlinControllerProxy(target: ActorRef) extends Actor {
    override def receive = {
      case msg ⇒ target.forward(msg)
    }
  }
}

class SplitBrainResolverIntegrationSpecMultiJvmNode1 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode2 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode3 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode4 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode5 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode6 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode7 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode8 extends SplitBrainResolverIntegrationSpec
class SplitBrainResolverIntegrationSpecMultiJvmNode9 extends SplitBrainResolverIntegrationSpec

class SplitBrainResolverIntegrationSpec extends MultiNodeSpec(SplitBrainResolverIntegrationSpec)
  with STMultiNodeSpec with ImplicitSender with BeforeAndAfterEach {
  import SplitBrainResolverIntegrationSpec._

  override def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s ⇒ new File(system.settings.config.getString(s)))

  override protected def atStartup(): Unit = {
    runOn(node1) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination(): Unit = {
    runOn(node1) {
      storageLocations.foreach(dir ⇒ if (dir.exists) FileUtils.deleteDirectory(dir))
    }
  }

  override def afterEach(): Unit = {
    if (disposableSys ne null)
      disposableSys.shutdownSys()
  }

  // counter for unique naming for each test
  var c = 0
  // to be shutdown in afterEach
  var disposableSys: DisposableSys = _

  object DisposableSys {
    def apply(scenario: Scenario): DisposableSys = {
      disposableSys = new DisposableSys(scenario)
      disposableSys
    }
  }

  class DisposableSys(scenario: Scenario) {

    c += 1

    val sys: ActorSystem = {
      val sys = ActorSystem(system.name + "-" + c,
        scenario.cfg.withFallback(system.settings.config))
      muteDeadLetters()(sys)
      val gremlinController = sys.actorOf(Props[GremlinController], "gremlinController")
      system.actorOf(Props(classOf[GremlinControllerProxy], gremlinController), s"gremlinControllerProxy-$c")
      sys
    }

    val singletonProbe = TestProbe()
    val shardingProbe = TestProbe()
    runOn(node1) {
      system.actorOf(Props(classOf[GlobalRegistry], singletonProbe.ref, false), s"singletonRegistry-$c")
      system.actorOf(Props(classOf[GlobalRegistry], shardingProbe.ref, true), s"shardingRegistry-$c")
    }
    enterBarrier("registry-started")
    system.actorSelection(node(node1) / "user" / s"singletonRegistry-$c") ! Identify(None)
    val singletonRegistry: ActorRef = expectMsgType[ActorIdentity].ref.get
    system.actorSelection(node(node1) / "user" / s"shardingRegistry-$c") ! Identify(None)
    val shardingRegistry: ActorRef = expectMsgType[ActorIdentity].ref.get
    enterBarrier("registry-located")

    lazy val region = ClusterSharding(sys).shardRegion(s"Entity-$c")

    def shutdownSys(): Unit = {
      TestKit.shutdownActorSystem(sys, 10.seconds, verifySystemShutdown = true)
    }

    def gremlinControllerProxy(at: RoleName): ActorRef = {
      system.actorSelection(node(at) / "user" / s"gremlinControllerProxy-$c") ! Identify(None)
      expectMsgType[ActorIdentity].ref.get
    }

    def sysAddress(at: RoleName): Address = {
      import system.dispatcher
      implicit val timeout = Timeout(3.seconds)
      Await.result((gremlinControllerProxy(at) ? GetAddress).mapTo[Address], timeout.duration)
    }

    def blackhole(from: RoleName, to: RoleName): Unit = {
      implicit val timeout = Timeout(3.seconds)
      import system.dispatcher
      val f = for {
        target ← (gremlinControllerProxy(to) ? GetAddress).mapTo[Address]
        done ← gremlinControllerProxy(from) ? BlackholeNode(target)
      } yield done
      Await.ready(f, timeout.duration * 2)
    }

    def join(from: RoleName, to: RoleName, awaitUp: Boolean): Unit = {
      runOn(from) {
        // start the Persistence extension
        Persistence(sys)
        val probe = TestProbe()(sys)
        sys.actorSelection(node(node1).address + "/user/store").tell(Identify(None), probe.ref)
        val sharedStore = probe.expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, sys)

        Cluster(sys) join sysAddress(to)
        createSingleton()
        startSharding()
        if (awaitUp)
          awaitMemberUp()
      }
      enterBarrier(from.name + "-joined-$c")
    }

    def awaitMemberUp(): Unit =
      within(10.seconds) {
        awaitAssert {
          Cluster(sys).state.members.exists { m ⇒
            m.address == Cluster(sys).selfAddress && m.status == MemberStatus.Up
          } should be(true)
        }
      }

    def createSingleton(): ActorRef = {
      sys.actorOf(ClusterSingletonManager.props(
        singletonProps = Props(classOf[SingletonActor], singletonRegistry),
        singletonName = "instance",
        terminationMessage = PoisonPill,
        role = None),
        name = "singleton")
    }

    def startSharding(): Unit = {
      ClusterSharding(sys).start(
        typeName = s"Entity-$c",
        entryProps = Some(Props(classOf[SingletonActor], shardingRegistry)),
        idExtractor = idExtractor,
        shardResolver = shardResolver)
    }

    def verify(): Unit = {
      val side1 = roles.take(scenario.side1Size)
      val side2 = roles.drop(scenario.side1Size).take(scenario.side2Size)

      within(90.seconds) {

        join(side1.head, side1.head, awaitUp = true) // oldest
        join(side2.head, side1.head, awaitUp = true) // next oldest
        for (n ← side1.tail ++ side2.tail)
          join(n, side1.head, awaitUp = false)
        runOn((side1 ++ side2): _*) {
          awaitMemberUp()
        }
        enterBarrier("all-up-$c")

        runOn(node1) {
          singletonProbe.within(25.seconds) {
            singletonProbe.expectMsg(Register("/user/singleton/instance", sysAddress(node1)))
          }
          shardingProbe.expectNoMsg(100.millis)
        }

        runOn((side1 ++ side2): _*) {
          val probe = TestProbe()(sys)
          for (i ← 0 until 10) {
            region.tell(i, probe.ref)
            probe.expectMsg(5.seconds, i)
          }
        }

        enterBarrier("initialized-$c")
        runOn((side1 ++ side2): _*) {
          log.info("Initialized {} in round {}", Cluster(sys).selfAddress, c)
        }

        runOn(node1) {
          for (n1 ← side1; n2 ← side2)
            blackhole(n1, n2)
        }
        enterBarrier(s"blackhole-$c")

        val resolvedExpected = scenario.expected match {
          case KeepLeader ⇒
            import Member.addressOrdering
            val address = (side1 ++ side2).map(sysAddress).sorted.head
            if (side1.exists(sysAddress(_) == address)) KeepSide1
            else if (side2.exists(sysAddress(_) == address)) KeepSide2
            else ShutdownBoth
          case other ⇒ other
        }

        resolvedExpected match {
          case ShutdownBoth ⇒
            runOn((side1 ++ side2): _*) {
              awaitCond(Cluster(sys).isTerminated, max = 30.seconds)
            }
            enterBarrier(s"sys-terminated-$c")
            runOn(node1) {
              singletonProbe.within(20.seconds) {
                singletonProbe.expectMsg(Unregister("/user/singleton/instance", sysAddress(side1.head)))
              }
              shardingProbe.expectNoMsg(100.millis)
            }

          case KeepSide1 ⇒
            runOn(side1: _*) {
              val expectedAddresses = side1.map(sysAddress).toSet
              within(remaining - 3.seconds) {
                awaitAssert {
                  val probe = TestProbe()(sys)
                  for (i ← 0 until 10) {
                    region.tell(i, probe.ref)
                    probe.expectMsg(2.seconds, i)
                  }

                  Cluster(sys).state.members.map(_.address) should be(expectedAddresses)
                }
              }
            }
            runOn(side2: _*) {
              awaitCond(Cluster(sys).isTerminated, max = 30.seconds)
            }
            enterBarrier(s"cluster-shutdown-verified-$c")
            singletonProbe.expectNoMsg(1.second)
            shardingProbe.expectNoMsg(100.millis)

          case KeepSide2 ⇒
            runOn(side1: _*) {
              awaitCond(Cluster(sys).isTerminated, max = 30.seconds)
            }
            enterBarrier(s"sys-terminated-$c")
            runOn(node1) {
              singletonProbe.within(30.seconds) {
                singletonProbe.expectMsg(Unregister("/user/singleton/instance", sysAddress(side1.head)))
                singletonProbe.expectMsg(Register("/user/singleton/instance", sysAddress(side2.head)))
              }
              shardingProbe.expectNoMsg(100.millis)
            }
            runOn(side2: _*) {
              val expectedAddresses = side2.map(sysAddress).toSet
              within(remaining - 3.seconds) {
                awaitAssert {
                  val probe = TestProbe()(sys)
                  for (i ← 0 until 10) {
                    region.tell(i, probe.ref)
                    probe.expectMsg(2.seconds, i)
                  }

                  Cluster(sys).state.members.map(_.address) should be(expectedAddresses)
                }
              }
            }

            enterBarrier(s"cluster-shutdown-verified-$c")

            runOn(side2: _*) {
              Cluster(sys).isTerminated should be(false)
            }

            runOn(node1) {
              shardingProbe.expectNoMsg(100.millis)
            }
            enterBarrier("singleton-moved-$c")

            runOn(side2: _*) {
              Cluster(sys).isTerminated should be(false)
            }
        }

        enterBarrier("verified-$c")
      }
      enterBarrier("after-$c")
    }

  }

  val staticQuorumConfig = ConfigFactory.parseString(
    """akka.cluster.split-brain-resolver {
        active-strategy = static-quorum
        static-quorum.quorum-size = 5
      }""")

  val keepMajorigyConfig = ConfigFactory.parseString(
    """akka.cluster.split-brain-resolver {
        active-strategy = keep-majority
      }""")
  val keepOldestConfig = ConfigFactory.parseString(
    """akka.cluster.split-brain-resolver {
        active-strategy = keep-oldest
      }""")

  trait Expected
  case object KeepSide1 extends Expected
  case object KeepSide2 extends Expected
  case object ShutdownBoth extends Expected
  case object KeepLeader extends Expected

  case class Scenario(
    cfg: Config,
    side1Size: Int,
    side2Size: Int,
    expected: Expected) {

    override def toString: String = {
      val activeStrategy = "akka.cluster.split-brain-resolver.active-strategy"
      s"$expected when using ${cfg.getString(activeStrategy)} and side1=$side1Size and side2=$side2Size"
    }
  }

  val scenarios = List(
    Scenario(staticQuorumConfig, 1, 2, ShutdownBoth),
    Scenario(staticQuorumConfig, 4, 4, ShutdownBoth),
    Scenario(staticQuorumConfig, 5, 4, KeepSide1),
    Scenario(staticQuorumConfig, 1, 5, KeepSide2),
    Scenario(staticQuorumConfig, 4, 5, KeepSide2),
    Scenario(keepMajorigyConfig, 2, 1, KeepSide1),
    Scenario(keepMajorigyConfig, 1, 2, KeepSide2),
    Scenario(keepMajorigyConfig, 4, 5, KeepSide2),
    Scenario(keepMajorigyConfig, 4, 4, KeepLeader),
    Scenario(keepOldestConfig, 3, 3, KeepSide1),
    Scenario(keepOldestConfig, 1, 1, KeepSide1),
    Scenario(keepOldestConfig, 1, 2, KeepSide2) // because down-if-alone
    )

  "Cluster SplitBrainResolver" must {

    "start shared journal" in {
      runOn(node1) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("shared-journal-started")
    }

    for (scenario ← scenarios) {
      scenario.toString in {
        DisposableSys(scenario).verify()
      }
    }
  }

}

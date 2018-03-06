/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.ClusterShardingStats
import akka.cluster.sharding.ShardRegion.GetClusterShardingStats
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object RollingUpgradeSpec extends MultiNodeConfig {
  // old, plain classic
  val node1 = role("node1")
  val node2 = role("node2")
  // hybrid-1, classic as default
  val node3 = role("node3")
  val node4 = role("node4")
  // hybrid-2, artery as default
  val node5 = role("node5")
  val node6 = role("node6")
  // new, plain artery
  val node7 = role("node7")
  val node8 = role("node8")

  val classicPorts = Map(
    node1 -> 2551,
    node2 -> 2552,
    node3 -> 2553,
    node4 -> 2554,
    node5 -> 2555,
    node6 -> 2556,
    node7 -> 2557,
    node8 -> 2558
  )

  val arteryPorts = Map(
    node1 -> 25510,
    node2 -> 25520,
    node3 -> 25530,
    node4 -> 25540,
    node5 -> 25550,
    node6 -> 25560,
    node7 -> 25570,
    node8 -> 25580
  )

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.cluster.auto-down-unreachable-after = 0s
    """))

  private def transportConfig(node: RoleName, arteryEnabled: Boolean, rollingMode: String): Config =
    ConfigFactory.parseString(s"""
      akka.remote.artery {
        enabled = $arteryEnabled
        transport = tcp
        hybrid-transport-rolling-upgrade-mode = "$rollingMode"
      }
      akka.remote.netty.tcp.port = ${classicPorts(node)}
      akka.remote.artery.canonical.port = ${arteryPorts(node)}
      """)

  nodeConfig(node1)(transportConfig(node1, arteryEnabled = false, rollingMode = ""))
  nodeConfig(node2)(transportConfig(node2, arteryEnabled = false, rollingMode = ""))
  nodeConfig(node3)(transportConfig(node3, arteryEnabled = true, rollingMode = "classic-as-default"))
  nodeConfig(node4)(transportConfig(node4, arteryEnabled = true, rollingMode = "classic-as-default"))
  nodeConfig(node5)(transportConfig(node5, arteryEnabled = true, rollingMode = "artery-as-default"))
  nodeConfig(node6)(transportConfig(node6, arteryEnabled = true, rollingMode = "artery-as-default"))
  nodeConfig(node7)(transportConfig(node7, arteryEnabled = true, rollingMode = ""))
  nodeConfig(node8)(transportConfig(node8, arteryEnabled = true, rollingMode = ""))

  final case class Ping(id: String)

  class Entity extends Actor {
    def receive = {
      case Ping(_) ⇒ sender() ! self
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case m @ Ping(id) ⇒ (id, m)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case Ping(id: String) ⇒ id.charAt(0).toString
  }
}

class RollingUpgradeSpecMultiJvmNode1 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode2 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode3 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode4 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode5 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode6 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode7 extends RollingUpgradeSpec
class RollingUpgradeSpecMultiJvmNode8 extends RollingUpgradeSpec

abstract class RollingUpgradeSpec extends MultiNodeSpec(RollingUpgradeSpec) with STMultiNodeSpec with ImplicitSender {

  import RollingUpgradeSpec._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      val joinAddress = from match {
        case `node1` | `node2` | `node3` | `node4` | `node5` | `node6` ⇒
          node(to).address.copy(protocol = "akka.tcp", port = Some(classicPorts(to)))
        case `node7` | `node8` ⇒
          node(to).address.copy(protocol = "akka", port = Some(arteryPorts(to)))
      }

      log.info(s"join $from (${cluster.selfAddress}) -> $to ($joinAddress)")
      cluster join joinAddress
      startSharding()
      within(15.seconds) {
        awaitAssert(cluster.state.members.exists { m ⇒
          m.uniqueAddress == cluster.selfUniqueAddress && m.status == MemberStatus.Up
        } should be(true))
      }
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {
    ClusterSharding(system).start(
      typeName = "Entity",
      entityProps = Props[Entity],
      settings = ClusterShardingSettings(system),
      extractEntityId = extractEntityId,
      extractShardId = extractShardId)
  }

  lazy val region = ClusterSharding(system).shardRegion("Entity")

  private def awaitRegionsRegistred(expected: Int): Unit = {
    within(10.seconds) {
      awaitAssert {
        val p = TestProbe()
        region.tell(GetClusterShardingStats(1.second), p.ref)
        p.expectMsgType[ClusterShardingStats].regions.size should ===(expected)
      }
    }
  }

  private def extractProtocol(ref: ActorRef): String =
    if (ref.path.address.hasGlobalScope) ref.path.address.protocol else cluster.selfAddress.protocol

  private def pingAll(max: Int): Map[String, ActorRef] = {
    (for (n ← 1 to max) yield {
      val id = n.toString
      region ! Ping(id)
      id → expectMsgType[ActorRef]
    }).toMap
  }

  private def runForAWhile(): Unit = {
    Thread.sleep(10000)
    enterBarrier("run")
  }

  "Rolling upgrade" must {

    // Step 1: old cluster running nodes with plain classic transport,
    // corresponding to e.g. Akka 2.5.11 without this feature

    "join old cluster" in within(20.seconds) {
      join(node1, node1)
      join(node2, node1)

      enterBarrier("after-1")
    }

    "initialize shards on old" in {
      runOn(node1) {
        awaitRegionsRegistred(2)
        val locations = pingAll(2)
        locations.size should ===(2)
        locations.values.map(extractProtocol).toSet should ===(Set("akka.tcp"))
      }
      enterBarrier("after-2")
    }

    "run old for a while" in {
      runForAWhile()

      runOn(node1, node2) {
        cluster.state.members.size should ===(2)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(2).size should ===(2)
      }
      enterBarrier("after-3")
    }

    // Step 2: join nodes with classic as the default transport,
    // this is to roll out this feature on all nodes, but still use classic transport

    "join hybrid-1 with classic as default" in {
      join(node3, node1)
      join(node4, node1)
      enterBarrier("after-4")
    }

    "initialize shards on hybrid-1" in {
      runOn(node1) {
        awaitRegionsRegistred(4)
        val locations = pingAll(4)
        locations.size should ===(4)
        locations.values.map(extractProtocol).toSet should ===(Set("akka.tcp"))
      }
      enterBarrier("after-5")
    }

    "run old and hybrid-1 for a while" in {
      runForAWhile()

      runOn(node1, node2, node3, node4) {
        cluster.state.members.size should ===(4)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(4).size should ===(4)
      }
      enterBarrier("after-6")
    }

    "remove old nodes" in {
      runOn(node1) {
        cluster.leave(node(node1).address)
        testConductor.exit(node2, 0).await
      }
      enterBarrier("stopped-old")
      runOn(node3, node4) {
        within(20.seconds) {
          awaitAssert {
            cluster.state.members.size should ===(2)
            cluster.state.unreachable should ===(Set.empty)
          }
        }
      }
      enterBarrier("after-7")
    }

    "run hybrid-1 for a while" in {
      runForAWhile()

      runOn(node3, node4) {
        cluster.state.members.size should ===(2)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(4).size should ===(4)
      }
      enterBarrier("after-8")
    }

    // Step 3: join nodes with Artery as the default transport

    "join hybrid-2 with artery as default" in {
      join(node5, node3)
      join(node6, node3)
      enterBarrier("after-9")
    }

    "initialize shards on hybrid-2" in {
      runOn(node3) {
        awaitRegionsRegistred(4)
        val locations = pingAll(6)
        locations.size should ===(6)
        locations.values.map(extractProtocol).toSet should ===(Set("akka.tcp", "akka"))
      }
      enterBarrier("after-10")
    }

    "run hybrid-1 and hybrid-2 for a while" in {
      runForAWhile()

      runOn(node3, node4, node5, node6) {
        cluster.state.members.size should ===(4)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(6).size should ===(6)
      }
      enterBarrier("after-11")
    }

    "remove hybrid-1 nodes" in {
      runOn(node5) {
        cluster.leave(node(node3).address)
      }
      runOn(node1) {
        testConductor.exit(node4, 0).await
      }
      enterBarrier("stopped-hybrid-1")
      runOn(node5, node6) {
        within(20.seconds) {
          awaitAssert {
            cluster.state.members.size should ===(2)
            cluster.state.unreachable should ===(Set.empty)
          }
        }
      }
      enterBarrier("after-12")
    }

    "run hybrid-2 for a while" in {
      runForAWhile()

      runOn(node5, node6) {
        cluster.state.members.size should ===(2)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(6).size should ===(6)
      }
      enterBarrier("after-13")
    }

    // Step 4: join nodes with plain Artery transport,
    // corresponding to using ordinary version again, e.g. 2.5.11

    "join new with plain artery" in {
      join(node7, node5)
      join(node8, node5)
      enterBarrier("after-14")
    }

    "initialize shards on new" in {
      runOn(node5) {
        awaitRegionsRegistred(4)
        val locations = pingAll(8)
        locations.size should ===(8)
        locations.values.map(extractProtocol).toSet should ===(Set("akka"))
      }
      enterBarrier("after-15")
    }

    "run hybrid-2 and new for a while" in {
      runForAWhile()

      runOn(node5, node6, node7, node8) {
        cluster.state.members.size should ===(4)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(8).size should ===(8)
      }
      enterBarrier("after-16")
    }

    "remove hybrid-2 nodes" in {
      runOn(node7) {
        cluster.leave(node(node5).address)
      }
      runOn(node1) {
        testConductor.exit(node6, 0).await
      }
      enterBarrier("stopped-hybrid-2")
      runOn(node7, node8) {
        within(20.seconds) {
          awaitAssert {
            cluster.state.members.size should ===(2)
            cluster.state.unreachable should ===(Set.empty)
          }
        }
      }
      enterBarrier("after-17")
    }

    "run new for a while" in {
      runForAWhile()

      runOn(node7, node8) {
        cluster.state.members.size should ===(2)
        cluster.state.unreachable should ===(Set.empty)
        pingAll(8).size should ===(8)
      }
      enterBarrier("after-18")
    }

  }
}


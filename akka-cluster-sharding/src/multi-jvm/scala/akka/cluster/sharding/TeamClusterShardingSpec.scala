/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.CurrentRegions
import akka.cluster.sharding.ShardRegion.GetCurrentRegions
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

object TeamClusterShardingSpec {
  sealed trait EntityMsg {
    def id: String
  }
  final case class Ping(id: String) extends EntityMsg
  final case class GetCount(id: String) extends EntityMsg

  class Entity extends Actor {
    var count = 0
    def receive = {
      case Ping(_) ⇒
        count += 1
        sender() ! self
      case GetCount(_) ⇒
        sender() ! count
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case m: EntityMsg ⇒ (m.id, m)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case m: EntityMsg ⇒ m.id.charAt(0).toString
  }
}

object TeamClusterShardingSpecConfig extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    """))

  nodeConfig(first, second) {
    ConfigFactory.parseString("akka.cluster.team = DC1")
  }

  nodeConfig(third, fourth) {
    ConfigFactory.parseString("akka.cluster.team = DC2")
  }
}

class TeamClusterShardingMultiJvmNode1 extends TeamClusterShardingSpec
class TeamClusterShardingMultiJvmNode2 extends TeamClusterShardingSpec
class TeamClusterShardingMultiJvmNode3 extends TeamClusterShardingSpec
class TeamClusterShardingMultiJvmNode4 extends TeamClusterShardingSpec

abstract class TeamClusterShardingSpec extends MultiNodeSpec(TeamClusterShardingSpecConfig)
  with STMultiNodeSpec with ImplicitSender {
  import TeamClusterShardingSpec._
  import TeamClusterShardingSpecConfig._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
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

  private def fillAddress(a: Address): Address =
    if (a.hasLocalScope) Cluster(system).selfAddress else a

  private def assertCurrentRegions(expected: Set[Address]): Unit = {
    awaitAssert({
      val p = TestProbe()
      region.tell(GetCurrentRegions, p.ref)
      p.expectMsg(CurrentRegions(expected))
    }, 10.seconds)
  }

  s"Cluster sharding with teams" must {
    "join cluster" in within(20.seconds) {
      join(first, first)
      join(second, first)
      join(third, first)
      join(fourth, first)

      awaitAssert({
        Cluster(system).state.members.size should ===(4)
        Cluster(system).state.members.map(_.status) should ===(Set(MemberStatus.Up))
      }, 10.seconds)

      runOn(first, second) {
        assertCurrentRegions(Set(first, second).map(r ⇒ node(r).address))
      }
      runOn(third, fourth) {
        assertCurrentRegions(Set(third, fourth).map(r ⇒ node(r).address))
      }

      enterBarrier("after-1")
    }

    "initialize shards" in {
      runOn(first) {
        val locations = (for (n ← 1 to 10) yield {
          val id = n.toString
          region ! Ping(id)
          id → expectMsgType[ActorRef]
        }).toMap
        val firstAddress = node(first).address
        val secondAddress = node(second).address
        val hosts = locations.values.map(ref ⇒ fillAddress(ref.path.address)).toSet
        hosts should ===(Set(firstAddress, secondAddress))
      }
      runOn(third) {
        val locations = (for (n ← 1 to 10) yield {
          val id = n.toString
          region ! Ping(id)
          val ref1 = expectMsgType[ActorRef]
          region ! Ping(id)
          val ref2 = expectMsgType[ActorRef]
          ref1 should ===(ref2)
          id → ref1
        }).toMap
        val thirdAddress = node(third).address
        val fourthAddress = node(fourth).address
        val hosts = locations.values.map(ref ⇒ fillAddress(ref.path.address)).toSet
        hosts should ===(Set(thirdAddress, fourthAddress))
      }
      enterBarrier("after-2")
    }

    "not mix entities in different teams" in {
      runOn(second) {
        region ! GetCount("5")
        expectMsg(1)
      }
      runOn(fourth) {
        region ! GetCount("5")
        expectMsg(2)
      }
      enterBarrier("after-3")
    }

    "allow proxy within same team" in {
      runOn(second) {
        val proxy = ClusterSharding(system).startProxy(
          typeName = "Entity",
          role = None,
          team = None, // by default use own team
          extractEntityId = extractEntityId,
          extractShardId = extractShardId)

        proxy ! GetCount("5")
        expectMsg(1)
      }
      enterBarrier("after-4")
    }

    "allow proxy across different teams" in {
      runOn(second) {
        val proxy = ClusterSharding(system).startProxy(
          typeName = "Entity",
          role = None,
          team = Some("DC2"), // proxy to other DC
          extractEntityId = extractEntityId,
          extractShardId = extractShardId)

        proxy ! GetCount("5")
        expectMsg(2)
      }
      enterBarrier("after-5")
    }

  }
}


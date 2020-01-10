/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.Future
import scala.concurrent.duration._

import akka.Done
import akka.actor._
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.MultiNodeClusterSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory

/**
 * Test for issue #28416
 */
object ClusterShardingRegistrationCoordinatedShutdownSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString(s"""
    akka.loglevel = DEBUG # FIXME
    """).withFallback(MultiNodeClusterSpec.clusterConfig))

  class Entity extends Actor {
    def receive = {
      case id: Int => sender() ! id
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int => (id.toString, id)
  }

  val extractShardId: ShardRegion.ExtractShardId = msg =>
    msg match {
      case id: Int => id.toString
    }

}

class ClusterShardingRegistrationCoordinatedShutdownMultiJvmNode1
    extends ClusterShardingRegistrationCoordinatedShutdownSpec
class ClusterShardingRegistrationCoordinatedShutdownMultiJvmNode2
    extends ClusterShardingRegistrationCoordinatedShutdownSpec
class ClusterShardingRegistrationCoordinatedShutdownMultiJvmNode3
    extends ClusterShardingRegistrationCoordinatedShutdownSpec

abstract class ClusterShardingRegistrationCoordinatedShutdownSpec
    extends MultiNodeSpec(ClusterShardingRegistrationCoordinatedShutdownSpec)
    with STMultiNodeSpec
    with ImplicitSender {
  import ClusterShardingRegistrationCoordinatedShutdownSpec._

  override def initialParticipants: Int = roles.size

  private val cluster = Cluster(system)
  private lazy val region = ClusterSharding(system).shardRegion("Entity")

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster.join(node(to).address)
    }
    runOn(from) {
      cluster.state.members.exists(m => m.uniqueAddress == cluster.selfUniqueAddress && m.status == MemberStatus.Up)
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

  s"Region registration during CoordinatedShutdown" must {

    "try next oldest" in within(30.seconds) {
      // second should be oldest
      join(second, second)
      join(first, second)
      join(third, second)

      awaitAssert {
        cluster.state.members.count(_.status == MemberStatus.Up) should ===(3)
      }

      val csTaskDone = TestProbe()
      runOn(third) {
        CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeClusterShutdown, "test")(() => {
          Thread.sleep(200)
          region ! 1
          expectMsg(1)
          csTaskDone.ref ! Done
          Future.successful(Done)
        })

      }

      startSharding()

      enterBarrier("before-shutdown")

      runOn(second) {
        CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason)
        awaitCond(cluster.isTerminated)
      }

      runOn(third) {
        CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason)
        awaitCond(cluster.isTerminated)
        csTaskDone.expectMsg(Done)
      }

      enterBarrier("after-shutdown")

      runOn(first) {
        region ! 2
        expectMsg(2)
        lastSender.path.address.hasLocalScope should ===(true)
      }

      enterBarrier("after-1")
    }
  }
}

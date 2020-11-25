/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed

import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.MemberStatus.Removed
import akka.cluster.sharding.typed.ClusterShardingPreparingForShutdownSpec.Pinger.Command
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.cluster.typed.MultiNodeTypedClusterSpec
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.serialization.jackson.CborSerializable
import com.typesafe.config.ConfigFactory

import scala.concurrent.duration._

object ClusterShardingPreparingForShutdownSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.downing-provider-class = akka.cluster.testkit.AutoDowning
    akka.cluster.testkit.auto-down-unreachable-after = off
    akka.cluster.leader-actions-interval = 100ms
    """))

  object Pinger {
    sealed trait Command extends CborSerializable
    case class Ping(id: Int, ref: ActorRef[Pong]) extends Command
    case class Pong(id: Int) extends CborSerializable

    def apply(): Behavior[Command] = Behaviors.setup { _ =>
      Behaviors.receiveMessage[Command] {
        case Ping(id: Int, ref) =>
          ref ! Pong(id)
          Behaviors.same
      }
    }

  }

  val typeKey = EntityTypeKey[Command]("ping")
}

class ClusterShardingPreparingForShutdownMultiJvmNode1 extends ClusterShardingPreparingForShutdownSpec
class ClusterShardingPreparingForShutdownMultiJvmNode2 extends ClusterShardingPreparingForShutdownSpec
class ClusterShardingPreparingForShutdownMultiJvmNode3 extends ClusterShardingPreparingForShutdownSpec

class ClusterShardingPreparingForShutdownSpec
    extends MultiNodeSpec(ClusterShardingPreparingForShutdownSpec)
    with MultiNodeTypedClusterSpec {
  import ClusterShardingPreparingForShutdownSpec._
  import ClusterShardingPreparingForShutdownSpec.Pinger._

  override def initialParticipants = roles.size

  private val sharding = ClusterSharding(typedSystem)

  "Preparing for shut down ClusterSharding" must {

    "form cluster" in {
      formCluster(first, second, third)
    }

    "not start new shards or rebalances when ready for shutdown" in {

      val shardRegion: ActorRef[ShardingEnvelope[Command]] =
        sharding.init(Entity(typeKey)(_ => Pinger()))

      val probe = TestProbe[Pong]()
      shardRegion ! ShardingEnvelope("id1", Pinger.Ping(1, probe.ref))
      probe.expectMessage(Pong(1))

      runOn(first) {
        // FIXME change to typed API
        Cluster(system).prepareForFullClusterShutdown()
      }
      awaitAssert({
        withClue("members: " + Cluster(system).readView.members) {
          Cluster(system).selfMember.status shouldEqual MemberStatus.ReadyForShutdown
          Cluster(system).readView.members.map(_.status) shouldEqual Set(MemberStatus.ReadyForShutdown)
        }
      }, 10.seconds)
      enterBarrier("preparation-complete")

      shardRegion ! ShardingEnvelope("id2", Pinger.Ping(2, probe.ref))
      probe.expectNoMessage(3.seconds)

      runOn(first) {
        Cluster(system).leave(address(first))
      }
      awaitAssert({
        runOn(second, third) {
          withClue("members: " + Cluster(system).readView.members) {
            Cluster(system).readView.members.size shouldEqual 2
          }
        }
        runOn(first) {
          withClue("self member: " + Cluster(system).selfMember) {
            Cluster(system).selfMember.status shouldEqual MemberStatus.Removed
          }
        }
      }, 5.seconds) // keep this lower than coordinated shutdown timeout
      shardRegion ! ShardingEnvelope("id3", Pinger.Ping(3, probe.ref))
      probe.expectNoMessage(3.seconds)
      enterBarrier("new-shards-verified")

      runOn(second) {
        Cluster(system).leave(address(second))
        Cluster(system).leave(address(third))
      }
      awaitAssert({
        withClue("self member: " + Cluster(system).selfMember) {
          Cluster(system).selfMember.status shouldEqual Removed
        }
      }, 15.seconds)
      enterBarrier("done")
    }
  }
}
/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import scala.concurrent.duration._

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorSystem, Props }
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.cluster.{ Cluster, MemberStatus }
import akka.cluster.sharding.testkit.ClusterShardingConfig
import akka.testkit.TestActors.EchoActor
import akka.testkit.{ AkkaSpec, TestProbe }
import org.scalatest.concurrent.ScalaFutures

object ShardRegionSpec extends ClusterShardingConfig {

  val shardTypeName = "Caat"

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int => (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int => (msg % 10).toString
  }

  class EntityActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case msg => sender() ! s"ack ${msg}"
    }
  }
}

class ShardRegionSpec extends AkkaSpec(ShardRegionSpec.baseConfig) with ScalaFutures {
  import ShardRegionSpec._

  private val sysA = system
  private val sysB = ActorSystem(system.name, system.settings.config)

  private val region1 = startShard(sysA)
  private val region2 = startShard(sysB)

  private val regionProbes = Set((region1, TestProbe()(sysA)), (region2, TestProbe()(sysB)))
  private val events = Set(1, 2)

  override def beforeTermination(): Unit = shutdown(sysB)

  def startShard(sys: ActorSystem): ActorRef =
    ClusterSharding(sys).start(
      shardTypeName,
      Props[EchoActor](),
      ClusterShardingSettings(system),
      extractEntityId,
      extractShardId)

  def startProxy(sys: ActorSystem): ActorRef =
    ClusterSharding(sys).startProxy(shardTypeName, None, extractEntityId, extractShardId)

  "Cluster ShardRegion" must {

    "initialize cluster and allocate sharded actors" in {
      Cluster(sysA).join(Cluster(sysA).selfAddress) // coordinator on A
      awaitAssert(Cluster(sysA).selfMember.status shouldEqual MemberStatus.Up)
      Cluster(sysB).join(Cluster(sysA).selfAddress)

      within(10.seconds) {
        awaitAssert {
          Set(sysA, sysB).foreach { s =>
            Cluster(s).sendCurrentClusterState(testActor)
            expectMsgType[CurrentClusterState].members.size shouldEqual 2
          }
        }
      }

      within(10.seconds) {
        awaitAssert {
          for ((region, probe) <- regionProbes; event <- events) {
            region.tell(event, probe.ref)
            probe.expectMsg(1.seconds, event)
          }
        }
      }
    }

    "determine whether the ref to deliver buffered RestartShard events to should receive them" in {
      within(15.seconds) {
        awaitAssert {
          regionProbes.foreach {
            case (region, probe) =>
              region.tell(ShardRegion.GetShardRegionState, probe.ref)
              val states = probe.receiveWhile(messages = 2) {
                case msg: ShardRegion.CurrentShardRegionState => msg
              }
              val shardIds = for {
                state <- states
                shard <- state.shards
              } yield shard.shardId

              shardIds.forall { sid =>
                !Shard.isShard(shardTypeName, sid, region.path) &&
                Shard.isShard(shardTypeName, sid, region.path / sid)
              } shouldEqual true
          }
        }
      }
    }

    "only deliver RestartShard to the local region" in {
      // TODO set rememberEntities = on, stop then restart the shard to trigger event/buffering/delivery
    }

  }
}

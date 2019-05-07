/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{ Actor, ActorLogging, PoisonPill, Props }
import akka.cluster.TestLeaseExt
import akka.cluster.sharding.ShardRegion.ShardInitialized
import akka.coordination.lease.LeaseUsageSettings
import akka.testkit.{ AkkaSpec, ImplicitSender, TestProbe }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NoStackTrace

object ShardSpec {
  val config =
    """
  akka.loglevel = INFO
  akka.actor.provider = "cluster"
  akka.remote.classic.netty.tcp.port = 0
  akka.remote.artery.canonical.port = 0
  test-lease {
      lease-class = akka.cluster.TestLease
      heartbeat-interval = 1s
      heartbeat-timeout = 120s
      lease-operation-timeout = 3s
  }
  """

  class EntityActor extends Actor with ActorLogging {
    override def receive: Receive = {
      case msg =>
        log.info("Msg {}", msg)
        sender() ! s"ack ${msg}"
    }
  }

  val numberOfShards = 5

  case class EntityEnvelope(entityId: Int, msg: Any)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case EntityEnvelope(id, payload) => (id.toString, payload)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case EntityEnvelope(id, _) => (id % numberOfShards).toString
  }

  case class BadLease(msg: String) extends RuntimeException(msg) with NoStackTrace
}

class ShardSpec extends AkkaSpec(ShardSpec.config) with ImplicitSender {

  import ShardSpec._

  val shortDuration = 100.millis
  val testLeaseExt = TestLeaseExt(system)

  def leaseNameForShard(typeName: String, shardId: String) = s"${system.name}-shard-${typeName}-${shardId}"

  "A Cluster Shard" should {
    "not initialize the shard until the lease is acquired" in new Setup {
      parent.expectNoMessage(shortDuration)
      lease.initialPromise.complete(Success(true))
      parent.expectMsg(ShardInitialized(shardId))
    }

    "retry if lease acquire returns false" in new Setup {
      lease.initialPromise.complete(Success(false))
      parent.expectNoMessage(shortDuration)
      lease.setNextAcquireResult(Future.successful(true))
      parent.expectMsg(ShardInitialized(shardId))
    }

    "retry if the lease acquire fails" in new Setup {
      lease.initialPromise.failure(BadLease("no lease for you"))
      parent.expectNoMessage(shortDuration)
      lease.setNextAcquireResult(Future.successful(true))
      parent.expectMsg(ShardInitialized(shardId))
    }

    "shutdown if lease is lost" in new Setup {
      val probe = TestProbe()
      probe.watch(shard)
      lease.initialPromise.complete(Success(true))
      parent.expectMsg(ShardInitialized(shardId))
      lease.getCurrentCallback().apply(Some(BadLease("bye bye lease")))
      probe.expectTerminated(shard)
    }
  }

  val shardIds = new AtomicInteger(0)
  def nextShardId = s"${shardIds.getAndIncrement()}"

  trait Setup {
    val shardId = nextShardId
    val parent = TestProbe()
    val settings = ClusterShardingSettings(system).withLeaseSettings(new LeaseUsageSettings("test-lease", 2.seconds))
    def lease = awaitAssert {
      testLeaseExt.getTestLease(leaseNameForShard(typeName, shardId))
    }

    val typeName = "type1"
    val shard = parent.childActorOf(
      Shard.props(
        typeName,
        shardId,
        _ => Props(new EntityActor()),
        settings,
        extractEntityId,
        extractShardId,
        PoisonPill,
        system.deadLetters,
        1))
  }

}

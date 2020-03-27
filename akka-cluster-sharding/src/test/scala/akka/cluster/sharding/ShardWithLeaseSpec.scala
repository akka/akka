/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.MemberStatus
import akka.cluster.TestLeaseExt
import akka.cluster.sharding.ShardRegion.ShardId
import akka.coordination.lease.LeaseUsageSettings
import akka.testkit.AkkaSpec
import akka.testkit.TestProbe

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NoStackTrace

object ShardWithLeaseSpec {
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

class ShardWithLeaseSpec extends AkkaSpec(ShardWithLeaseSpec.config) {

  import ShardWithLeaseSpec._

  val shortDuration = 100.millis
  val testLeaseExt = TestLeaseExt(system)

  override def atStartup(): Unit = {
    // Form a one node cluster
    val cluster = Cluster(system)
    cluster.join(cluster.selfAddress)
    awaitAssert(cluster.readView.members.count(_.status == MemberStatus.Up) should ===(1))
  }

  "Lease handling in sharding" must {
    "not initialize the shard until the lease is acquired" in new Setup {
      val probe = TestProbe()
      sharding.tell(EntityEnvelope(1, "hello"), probe.ref)
      probe.expectNoMessage(shortDuration)
      leaseFor("1").initialPromise.complete(Success(true))
      probe.expectMsg("ack hello")
    }

    "retry if lease acquire returns false" in new Setup {
      val probe = TestProbe()
      sharding.tell(EntityEnvelope(1, "hello"), probe.ref)
      val lease = leaseFor("1")
      lease.initialPromise.complete(Success(false))
      probe.expectNoMessage(shortDuration)
      lease.setNextAcquireResult(Future.successful(true))
      probe.expectMsg("ack hello")
    }

    "retry if the lease acquire fails" in new Setup {
      val probe = TestProbe()
      sharding.tell(EntityEnvelope(1, "hello"), probe.ref)
      val lease = leaseFor("1")
      lease.initialPromise.failure(BadLease("no lease for you"))
      probe.expectNoMessage(shortDuration)
      lease.setNextAcquireResult(Future.successful(true))
      probe.expectMsg("ack hello")
    }

    "shutdown if lease is lost" in new Setup {
      val probe = TestProbe()
      sharding.tell(EntityEnvelope(1, "hello"), probe.ref)
      val lease = leaseFor("1")
      lease.initialPromise.complete(Success(true))
      probe.expectMsg("ack hello")

      lease.getCurrentCallback().apply(Some(BadLease("bye bye lease")))
      sharding.tell(EntityEnvelope(1, "hello"), probe.ref)
      probe.expectNoMessage(shortDuration)
    }
  }

  var typeIdx = 0

  trait Setup {
    val settings = ClusterShardingSettings(system).withLeaseSettings(new LeaseUsageSettings("test-lease", 2.seconds))

    // unique type name for each test
    val typeName = {
      typeIdx += 1
      s"type$typeIdx"
    }

    val sharding =
      ClusterSharding(system).start(typeName, Props(new EntityActor()), settings, extractEntityId, extractShardId)

    def leaseFor(shardId: ShardId) = awaitAssert {
      val leaseName = s"${system.name}-shard-${typeName}-${shardId}"
      testLeaseExt.getTestLease(leaseName)
    }
  }

}

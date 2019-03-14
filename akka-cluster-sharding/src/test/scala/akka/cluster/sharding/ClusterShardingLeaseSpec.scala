/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding
import akka.actor.Props
import akka.cluster.{Cluster, MemberStatus, TestLease, TestLeaseExt}
import akka.testkit.TestActors.EchoActor
import akka.testkit.{AkkaSpec, ImplicitSender}
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NoStackTrace

object ClusterShardingLeaseSpec {
  val config = ConfigFactory.parseString("""
    akka.loglevel = INFO
    #akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.actor.provider = "cluster"
    akka.remote.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.cluster.sharding {
       lease-implementation = "test-lease"
       lease-retry-interval = 2000ms
     }
    """).withFallback(TestLease.config)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int => (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int => (msg % 10).toString
  }
  case class LeaseFailed(msg: String) extends RuntimeException(msg) with NoStackTrace
}

class ClusterShardingLeaseSpec extends AkkaSpec(ClusterShardingLeaseSpec.config) with ImplicitSender {
  import ClusterShardingLeaseSpec._

  val shortDuration = 100.millis
  val cluster = Cluster(system)
  val leaseOwner = cluster.selfMember.address.hostPort
  val testLeaseExt = TestLeaseExt(system)

  override protected def atStartup(): Unit = {
    cluster.join(cluster.selfAddress)
    awaitAssert {
      cluster.selfMember.status shouldEqual MemberStatus.Up
    }
    ClusterSharding(system).start(typeName = typeName,
                                  entityProps = Props[EchoActor],
                                  settings = ClusterShardingSettings(system),
                                  extractEntityId = extractEntityId,
                                  extractShardId = extractShardId)
  }

  def region = ClusterSharding(system).shardRegion(typeName)

  val typeName = "echo"

  def leaseForShard(shardId: Int) = awaitAssert {
    testLeaseExt.getTestLease(leaseNameFor(shardId))
  }

  def leaseNameFor(shardId: Int, typeName: String = typeName): String =
    s"${system.name}-shard-${typeName}-${shardId}"

  "Cluster sharding with lease" should {
    "not start until lease is acquired" in {
      region ! 1
      expectNoMessage()
      val testLease = leaseForShard(1)
      testLease.initialPromise.complete(Success(true))
      expectMsg(1)
    }
    "retry if initial acquire is false" in {
      region ! 2
      expectNoMessage()
      val testLease = leaseForShard(2)
      testLease.initialPromise.complete(Success(false))
      expectNoMessage()
      testLease.setNextAcquireResult(Future.successful(true))
      expectMsg(2)
    }
    "retry if initial acquire fails" in {
      region ! 3
      expectNoMessage()
      val testLease = leaseForShard(3)
      testLease.initialPromise.failure(LeaseFailed("oh no"))
      expectNoMessage()
      testLease.setNextAcquireResult(Future.successful(true))
      expectMsg(3)
    }
  }
}

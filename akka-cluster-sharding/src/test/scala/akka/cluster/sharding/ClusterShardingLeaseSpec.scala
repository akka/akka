/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding
import akka.actor.Props
import akka.cluster.{ Cluster, MemberStatus, TestLease, TestLeaseExt }
import akka.testkit.TestActors.EchoActor
import akka.testkit.{ AkkaSpec, ImplicitSender }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Success
import scala.util.control.NoStackTrace

object ClusterShardingLeaseSpec {
  val config = ConfigFactory.parseString("""
    akka.loglevel = DEBUG
    #akka.loggers = ["akka.testkit.SilenceAllTestEventListener"]
    akka.actor.provider = "cluster"
    akka.remote.classic.netty.tcp.port = 0
    akka.remote.artery.canonical.port = 0
    akka.cluster.sharding {
       use-lease = "test-lease"
       lease-retry-interval = 200ms
       distributed-data.durable {
        keys = []
       }
     }
    """).withFallback(TestLease.config)

  val persistenceConfig = ConfigFactory.parseString("""
      akka.cluster.sharding {
        state-store-mode = persistence
        journal-plugin-id = "akka.persistence.journal.inmem"
      }
    """)

  val ddataConfig = ConfigFactory.parseString("""
      akka.cluster.sharding {
        state-store-mode = ddata
      }
    """)

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case msg: Int => (msg.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case msg: Int => (msg % 10).toString
  }
  case class LeaseFailed(msg: String) extends RuntimeException(msg) with NoStackTrace
}

class PersistenceClusterShardingLeaseSpec
    extends ClusterShardingLeaseSpec(ClusterShardingLeaseSpec.persistenceConfig, true)
class DDataClusterShardingLeaseSpec extends ClusterShardingLeaseSpec(ClusterShardingLeaseSpec.ddataConfig, true)

class ClusterShardingLeaseSpec(config: Config, rememberEntities: Boolean)
    extends AkkaSpec(config.withFallback(ClusterShardingLeaseSpec.config))
    with ImplicitSender {
  import ClusterShardingLeaseSpec._

  def this() = this(ConfigFactory.empty(), false)

  val shortDuration = 200.millis
  val cluster = Cluster(system)
  val leaseOwner = cluster.selfMember.address.hostPort
  val testLeaseExt = TestLeaseExt(system)

  override protected def atStartup(): Unit = {
    cluster.join(cluster.selfAddress)
    awaitAssert {
      cluster.selfMember.status shouldEqual MemberStatus.Up
    }
    ClusterSharding(system).start(
      typeName = typeName,
      entityProps = Props[EchoActor],
      settings = ClusterShardingSettings(system).withRememberEntities(rememberEntities),
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
      expectNoMessage(shortDuration)
      val testLease = leaseForShard(1)
      testLease.initialPromise.complete(Success(true))
      expectMsg(1)
    }
    "retry if initial acquire is false" in {
      region ! 2
      expectNoMessage(shortDuration)
      val testLease = leaseForShard(2)
      testLease.initialPromise.complete(Success(false))
      expectNoMessage(shortDuration)
      testLease.setNextAcquireResult(Future.successful(true))
      expectMsg(2)
    }
    "retry if initial acquire fails" in {
      region ! 3
      expectNoMessage(shortDuration)
      val testLease = leaseForShard(3)
      testLease.initialPromise.failure(LeaseFailed("oh no"))
      expectNoMessage(shortDuration)
      testLease.setNextAcquireResult(Future.successful(true))
      expectMsg(3)
    }
    "recover if lease lost" in {
      region ! 4
      expectNoMessage(shortDuration)
      val testLease = leaseForShard(4)
      testLease.initialPromise.complete(Success(true))
      expectMsg(4)
      testLease.getCurrentCallback()(Option(LeaseFailed("oh dear")))
      awaitAssert({
        region ! 4
        expectMsg(4)
      }, max = 5.seconds)
    }
  }
}

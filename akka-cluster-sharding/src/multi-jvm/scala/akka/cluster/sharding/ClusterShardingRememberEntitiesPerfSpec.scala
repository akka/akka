/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding

import java.util.concurrent.TimeUnit.NANOSECONDS
import java.util.concurrent.atomic.AtomicInteger

import akka.actor._
import akka.cluster.MemberStatus
import akka.cluster.sharding.ShardRegion.Passivate
import akka.testkit._
import akka.util.ccompat._
import com.typesafe.config.ConfigFactory
import org.HdrHistogram.Histogram

import scala.concurrent.duration._

@ccompatUsedUntil213
object ClusterShardingRememberEntitiesPerfSpec {

  def props(): Props = Props(new TestEntity)

  class TestEntity extends Actor with ActorLogging {

    log.debug("Started TestEntity: {}", self)

    def receive = {
      case _: Stop => context.parent ! Passivate("stop")
      case "stop"  => context.stop(self)
      case m       => sender() ! m
    }
  }

  val extractEntityId: ShardRegion.ExtractEntityId = {
    case id: Int        => (id.toString, id)
    case msg @ Stop(id) => (id.toString, msg)
  }

  val extractShardId: ShardRegion.ExtractShardId = {
    case _: Int                     => "0" // only one shard
    case _: Stop                    => "0"
    case ShardRegion.StartEntity(_) => "0"
  }

  case class In(id: Long, created: Long = System.currentTimeMillis())
  case class Out(latency: Long)

  class LatencyEntity extends Actor with ActorLogging {

    override def receive: Receive = {
      case In(_, created) => sender() ! Out(System.currentTimeMillis() - created)
      case _: Stop        => context.parent ! Passivate("stop")
      case "stop"         => context.stop(self)
      case msg            => throw new RuntimeException("unexpected msg " + msg)
    }
  }

  object LatencyEntity {

    val extractEntityId: ShardRegion.ExtractEntityId = {
      case in: In         => (in.id.toString, in)
      case msg @ Stop(id) => (id.toString, msg)
    }

    val extractShardId: ShardRegion.ExtractShardId = _ => "0"
  }

  case class Stop(id: Int)

}

object ClusterShardingRememberEntitiesPerfSpecConfig
    extends MultiNodeClusterShardingConfig(
      rememberEntities = true,
      additionalConfig = s"""
    akka.testconductor.barrier-timeout = 3 minutes
    akka.remote.artery.advanced.outbound-message-queue-size = 10000
    akka.remote.artery.advanced.maximum-frame-size = 512 KiB
    # comment next line to enable durable lmdb storage
    akka.cluster.sharding.distributed-data.durable.keys = []
    akka.cluster.sharding {
      remember-entities = on
    }
    """) {

  val first = role("first")
  val second = role("second")
  val third = role("third")

  nodeConfig(third)(ConfigFactory.parseString(s"""
    akka.cluster.sharding.distributed-data.durable.lmdb {
      # use same directory when starting new node on third (not used at same time)
      dir = "$targetDir/sharding-third"
    }
    """))
}

class ClusterShardingRememberEntitiesPerfSpecMultiJvmNode1 extends ClusterShardingRememberEntitiesPerfSpec
class ClusterShardingRememberEntitiesPerfSpecMultiJvmNode2 extends ClusterShardingRememberEntitiesPerfSpec
class ClusterShardingRememberEntitiesPerfSpecMultiJvmNode3 extends ClusterShardingRememberEntitiesPerfSpec

abstract class ClusterShardingRememberEntitiesPerfSpec
    extends MultiNodeClusterShardingSpec(ClusterShardingRememberEntitiesPerfSpecConfig)
    with ImplicitSender {
  import ClusterShardingRememberEntitiesPerfSpec._
  import ClusterShardingRememberEntitiesPerfSpecConfig._

  val nrRegions = 6

  def startSharding(): Unit = {
    (1 to 4).foreach { n =>
      startSharding(
        system,
        typeName = s"Entity$n",
        entityProps = ClusterShardingRememberEntitiesPerfSpec.props(),
        extractEntityId = extractEntityId,
        extractShardId = extractShardId)
    }
    (1 to nrRegions).foreach { n =>
      startSharding(
        system,
        typeName = s"EntityLatency$n",
        entityProps = Props(new LatencyEntity()),
        extractEntityId = LatencyEntity.extractEntityId,
        extractShardId = LatencyEntity.extractShardId)
    }

  }

  lazy val region1 = ClusterSharding(system).shardRegion("Entity1")
  lazy val region2 = ClusterSharding(system).shardRegion("Entity2")
  lazy val region3 = ClusterSharding(system).shardRegion("Entity3")
  lazy val regionStartStop = ClusterSharding(system).shardRegion("Entity4")

  var latencyRegions = Vector.empty[ActorRef]

  // use 5 for "real" testing
  private val nrIterations = 5
  // use 5 for "real" testing
  private val numberOfMessagesFactor = 20

  val latencyCount = new AtomicInteger(0)

  "Cluster sharding with remember entities performance" must {

    "form cluster" in within(20.seconds) {
      join(first, first)

      startSharding()

      // this will make it run on first
      runOn(first) {
        region1 ! 0
        expectMsg(0)
        region2 ! 0
        expectMsg(0)
        region3 ! 0
        expectMsg(0)
        regionStartStop ! 0
        expectMsg(0)

        latencyRegions = (1 to nrRegions).map { n =>
          val region = ClusterSharding(system).shardRegion(s"EntityLatency$n")
          region ! In(0)
          expectMsgType[Out]
          region
        }.toVector
      }
      enterBarrier("allocated-on-first")

      join(second, first)
      join(third, first)

      within(remaining) {
        awaitAssert {
          cluster.state.members.size should ===(3)
          cluster.state.members.unsorted.map(_.status) should ===(Set(MemberStatus.Up))
        }
      }

      enterBarrier("all-up")
    }

    val percentiles = List(99.9, 99.0, 95.0, 50.0)

    def runBench(name: String)(logic: (Int, ActorRef, Histogram) => Int): Unit = {
      val testRun = latencyCount.getAndIncrement()
      runOn(first) {
        val region = latencyRegions(testRun)
        val fullHistogram = new Histogram(10 * 1000, 2)
        val throughputs = (1 to nrIterations).map { iteration =>
          val histogram: Histogram = new Histogram(10 * 1000, 2)
          val startTime = System.nanoTime()
          val numberOfMessages = logic(iteration, region, histogram)
          val took = NANOSECONDS.toMillis(System.nanoTime - startTime)
          val throughput = numberOfMessages * 1000.0 / took
          println(
            s"### Test [${name}] stop with $numberOfMessages took ${(System.nanoTime() - startTime) / 1000 / 1000} ms, " +
            f"throughput $throughput%,.0f msg/s")
          //          println("Iteration Latencies: ")
          //          histogram.outputPercentileDistribution(System.out, 1.0)
          fullHistogram.add(histogram)
          throughput
        }
        println(f"Average throughput: ${throughputs.sum / nrIterations}%,.0f msg/s")
        println("Combined latency figures:")
        println(
          s"max ${fullHistogram.getMaxValue} ${percentiles.map(p => s"$p% ${fullHistogram.getValueAtPercentile(p)}ms").mkString(" ")}")

      }
      enterBarrier(s"after-start-stop-${testRun}")
    }

    "test when starting new entity" in {
      val numberOfMessages = 200 * numberOfMessagesFactor
      runBench("start new entities") { (iteration, region, histogram) =>
        (1 to numberOfMessages).foreach { n =>
          region ! In(iteration * 100000 + n)
        }
        for (_ <- 1 to numberOfMessages) {
          histogram.recordValue(expectMsgType[Out].latency)
        }
        numberOfMessages
      }
    }

    "test latency when starting new entity and sending a few messages" in {
      val numberOfMessages = 800 * numberOfMessagesFactor
      runBench("start, few messages") { (iteration, region, histogram) =>
        for (n <- 1 to numberOfMessages / 5; _ <- 1 to 5) {
          region ! In(iteration * 100000 + n)
        }
        for (_ <- 1 to numberOfMessages) {
          histogram.recordValue(expectMsgType[Out].latency)
        }
        numberOfMessages
      }
    }

    "test latency when starting new entity and sending a few messages to it and stopping" in {
      val numberOfMessages = 800 * numberOfMessagesFactor
      runBench("start, few messages, stop") { (iteration, region, histogram) =>
        for (n <- 1 to numberOfMessages / 5; m <- 1 to 6) {
          region ! In(iteration * 100000 + n)
          if (m == 6)
            region ! Stop(iteration * 100000 + n)
        }
        for (_ <- 1 to numberOfMessages) {
          histogram.recordValue(expectMsgType[Out].latency)
        }
        numberOfMessages
      }
    }

    "test when starting some new entities mixed with sending to started" in {
      runBench("starting mixed with sending to started") { (iteration, region, histogram) =>
        val numberOfMessages = 1600 * numberOfMessagesFactor
        (1 to numberOfMessages).foreach { n =>
          val msg =
            if (n % 20 == 0)
              -(iteration * 100000 + n) // unique, will start new entity
            else
              iteration * 100000 + (n % 10) // these will go to same 10 started entities
          region ! In(msg)

          if (n == 10) {
            for (_ <- 1 to 10) {
              histogram.recordValue(expectMsgType[Out].latency)
            }
          }
        }
        for (_ <- 1 to numberOfMessages - 10) {
          histogram.recordValue(expectMsgType[Out].latency)
        }
        numberOfMessages
      }
    }

    "test sending to started" in {
      runBench("sending to started") { (iteration, region, histogram) =>
        val numberOfMessages = 1600 * numberOfMessagesFactor
        (1 to numberOfMessages).foreach { n =>
          region ! In(iteration * 100000 + (n % 10)) // these will go to same 10 started entities

          if (n == 10) {
            for (_ <- 1 to 10) {
              histogram.recordValue(expectMsgType[Out].latency)
            }
          }
        }
        for (_ <- 1 to numberOfMessages - 10) {
          histogram.recordValue(expectMsgType[Out].latency)
        }
        numberOfMessages
      }
    }
  }

}

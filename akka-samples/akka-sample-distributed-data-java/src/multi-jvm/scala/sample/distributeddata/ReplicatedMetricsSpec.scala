package sample.distributeddata

import scala.concurrent.duration._
import akka.cluster.Cluster
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._

object ReplicatedMetricsSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.log-dead-letters-during-shutdown = off
    """))

}

class ReplicatedMetricsSpecMultiJvmNode1 extends ReplicatedMetricsSpec
class ReplicatedMetricsSpecMultiJvmNode2 extends ReplicatedMetricsSpec
class ReplicatedMetricsSpecMultiJvmNode3 extends ReplicatedMetricsSpec

class ReplicatedMetricsSpec extends MultiNodeSpec(ReplicatedMetricsSpec) with STMultiNodeSpec with ImplicitSender {
  import ReplicatedMetricsSpec._
  import ReplicatedMetrics._

  override def initialParticipants = roles.size

  val cluster = Cluster(system)
  val replicatedMetrics = system.actorOf(ReplicatedMetrics.props(1.second, 3.seconds))

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
    }
    enterBarrier(from.name + "-joined")
  }

  "Demo of a replicated metrics" must {
    "join cluster" in within(20.seconds) {
      join(node1, node1)
      join(node2, node1)
      join(node3, node1)

      awaitAssert {
        DistributedData(system).replicator ! GetReplicaCount
        expectMsg(ReplicaCount(roles.size))
      }
      enterBarrier("after-1")
    }

    "replicate metrics" in within(10.seconds) {
      val probe = TestProbe()
      system.eventStream.subscribe(probe.ref, classOf[UsedHeap])
      awaitAssert {
        probe.expectMsgType[UsedHeap](1.second).percentPerNode.size should be(3)
      }
      probe.expectMsgType[UsedHeap].percentPerNode.size should be(3)
      probe.expectMsgType[UsedHeap].percentPerNode.size should be(3)
      enterBarrier("after-2")
    }

    "cleanup removed node" in within(25.seconds) {
      val node3Address = node(node3).address
      runOn(node1) {
        cluster.leave(node3Address)
      }
      runOn(node1, node2) {
        val probe = TestProbe()
        system.eventStream.subscribe(probe.ref, classOf[UsedHeap])
        awaitAssert {
          probe.expectMsgType[UsedHeap](1.second).percentPerNode.size should be(2)
        }
        probe.expectMsgType[UsedHeap].percentPerNode.asScala.toMap should not contain (
          nodeKey(node3Address))
      }
      enterBarrier("after-3")
    }

  }

}


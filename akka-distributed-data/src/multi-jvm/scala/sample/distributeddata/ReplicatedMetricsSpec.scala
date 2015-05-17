/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.datareplication

import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Address
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{ InitialStateAsEvents, MemberUp, MemberRemoved }
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.Replicator.GetReplicaCount
import akka.cluster.ddata.Replicator.ReplicaCount
import akka.cluster.ddata.STMultiNodeSpec
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.cluster.ddata.LWWMapKey

object ReplicatedMetricsSpec extends MultiNodeConfig {
  val node1 = role("node-1")
  val node2 = role("node-2")
  val node3 = role("node-3")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.log-dead-letters-during-shutdown = off
    """))

}

object ReplicatedMetrics {
  import akka.cluster.ddata.Replicator._

  def props(measureInterval: FiniteDuration, cleanupInterval: FiniteDuration): Props =
    Props(new ReplicatedMetrics(measureInterval, cleanupInterval))

  def props: Props = props(1.second, 1.minute)

  private case object Tick
  private case object Cleanup

  case class UsedHeap(percentPerNode: Map[String, Double]) {
    override def toString =
      percentPerNode.toSeq.sortBy(_._1).map {
        case (key, value) ⇒ key + " --> " + value + " %"
      }.mkString("\n")
  }

  def nodeKey(address: Address): String = address.host.get + ":" + address.port.get

}

class ReplicatedMetrics(measureInterval: FiniteDuration, cleanupInterval: FiniteDuration)
  extends Actor with ActorLogging {
  import akka.cluster.ddata.Replicator._
  import ReplicatedMetrics._

  val replicator = DistributedData(context.system).replicator
  implicit val cluster = Cluster(context.system)
  val node = nodeKey(cluster.selfAddress)

  val tickTask = context.system.scheduler.schedule(measureInterval, measureInterval,
    self, Tick)(context.dispatcher)
  val cleanupTask = context.system.scheduler.schedule(cleanupInterval, cleanupInterval,
    self, Cleanup)(context.dispatcher)
  val memoryMBean: MemoryMXBean = ManagementFactory.getMemoryMXBean

  val UsedHeapKey = LWWMapKey[Long]("usedHeap")
  val MaxHeapKey = LWWMapKey[Long]("maxHeap")

  replicator ! Subscribe(UsedHeapKey, self)
  replicator ! Subscribe(MaxHeapKey, self)

  cluster.subscribe(self, InitialStateAsEvents, classOf[MemberUp], classOf[MemberRemoved])

  override def postStop(): Unit = {
    tickTask.cancel()
    cluster.unsubscribe(self)
    super.postStop()
  }

  var maxHeap = Map.empty[String, Long]
  var nodesInCluster = Set.empty[String]

  def receive = {
    case Tick ⇒
      val heap = memoryMBean.getHeapMemoryUsage
      val used = heap.getUsed
      val max = heap.getMax
      replicator ! Update(UsedHeapKey, LWWMap.empty[Long], WriteLocal)(_ + (node -> used))
      replicator ! Update(MaxHeapKey, LWWMap.empty[Long], WriteLocal) { data ⇒
        data.get(node) match {
          case Some(`max`) ⇒ data // unchanged
          case _           ⇒ data + (node -> max)
        }
      }

    case c @ Changed(MaxHeapKey) ⇒
      maxHeap = c.get(MaxHeapKey).entries

    case c @ Changed(UsedHeapKey) ⇒
      val usedHeapPercent = UsedHeap(c.get(UsedHeapKey).entries.collect {
        case (key, value) if maxHeap.contains(key) ⇒
          (key -> (value.toDouble / maxHeap(key)) * 100.0)
      })
      log.debug("Node {} observed:\n{}", node, usedHeapPercent)
      context.system.eventStream.publish(usedHeapPercent)

    case _: UpdateResponse[_] ⇒ // ok

    case MemberUp(m) ⇒
      nodesInCluster += nodeKey(m.address)

    case MemberRemoved(m, _) ⇒
      nodesInCluster -= nodeKey(m.address)

    case Cleanup ⇒
      def cleanupRemoved(data: LWWMap[Long]): LWWMap[Long] =
        (data.entries.keySet -- nodesInCluster).foldLeft(data) { case (d, key) ⇒ d - key }

      replicator ! Update(UsedHeapKey, LWWMap.empty[Long], WriteLocal)(cleanupRemoved)
      replicator ! Update(MaxHeapKey, LWWMap.empty[Long], WriteLocal)(cleanupRemoved)
  }

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
    "join cluster" in within(10.seconds) {
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
        probe.expectMsgType[UsedHeap].percentPerNode.size should be(3)
      }
      probe.expectMsgType[UsedHeap].percentPerNode.size should be(3)
      probe.expectMsgType[UsedHeap].percentPerNode.size should be(3)
      enterBarrier("after-2")
    }

    "cleanup removed node" in within(15.seconds) {
      val node3Address = node(node3).address
      runOn(node1) {
        cluster.leave(node3Address)
      }
      runOn(node1, node2) {
        val probe = TestProbe()
        system.eventStream.subscribe(probe.ref, classOf[UsedHeap])
        awaitAssert {
          probe.expectMsgType[UsedHeap].percentPerNode.size should be(2)
        }
        probe.expectMsgType[UsedHeap].percentPerNode should not contain (
          nodeKey(node3Address))
      }
      enterBarrier("after-3")
    }

  }

}


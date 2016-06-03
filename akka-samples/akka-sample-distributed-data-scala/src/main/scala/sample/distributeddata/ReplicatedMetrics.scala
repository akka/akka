package sample.distributeddata

import java.lang.management.ManagementFactory
import java.lang.management.MemoryMXBean
import scala.concurrent.duration._
import scala.concurrent.duration.FiniteDuration
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Address
import akka.actor.Props
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.InitialStateAsEvents
import akka.cluster.ClusterEvent.MemberRemoved
import akka.cluster.ClusterEvent.MemberUp
import akka.cluster.ddata.DistributedData
import akka.cluster.ddata.LWWMap
import akka.cluster.ddata.LWWMapKey

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
        case (key, value) => key + " --> " + value + " %"
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
    case Tick =>
      val heap = memoryMBean.getHeapMemoryUsage
      val used = heap.getUsed
      val max = heap.getMax
      replicator ! Update(UsedHeapKey, LWWMap.empty[Long], WriteLocal)(_ + (node -> used))
      replicator ! Update(MaxHeapKey, LWWMap.empty[Long], WriteLocal) { data =>
        data.get(node) match {
          case Some(`max`) => data // unchanged
          case _           => data + (node -> max)
        }
      }

    case c @ Changed(MaxHeapKey) =>
      maxHeap = c.get(MaxHeapKey).entries

    case c @ Changed(UsedHeapKey) =>
      val usedHeapPercent = UsedHeap(c.get(UsedHeapKey).entries.collect {
        case (key, value) if maxHeap.contains(key) =>
          (key -> (value.toDouble / maxHeap(key)) * 100.0)
      })
      log.debug("Node {} observed:\n{}", node, usedHeapPercent)
      context.system.eventStream.publish(usedHeapPercent)

    case _: UpdateResponse[_] => // ok

    case MemberUp(m) =>
      nodesInCluster += nodeKey(m.address)

    case MemberRemoved(m, _) =>
      nodesInCluster -= nodeKey(m.address)
      if (m.address == cluster.selfAddress)
        context.stop(self)

    case Cleanup =>
      def cleanupRemoved(data: LWWMap[Long]): LWWMap[Long] =
        (data.entries.keySet -- nodesInCluster).foldLeft(data) { case (d, key) => d - key }

      replicator ! Update(UsedHeapKey, LWWMap.empty[Long], WriteLocal)(cleanupRemoved)
      replicator ! Update(MaxHeapKey, LWWMap.empty[Long], WriteLocal)(cleanupRemoved)
  }

}

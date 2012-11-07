/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import language.postfixOps
import scala.concurrent.duration._
import java.lang.management.ManagementFactory
import akka.remote.testkit.{ MultiNodeSpec, MultiNodeConfig }
import akka.testkit.{ LongRunningTest, DefaultTimeout, ImplicitSender }
import akka.actor._
import akka.cluster.{ MemberStatus, MultiNodeClusterSpec }
import scala.concurrent.Await
import akka.routing.RouterRoutees
import akka.pattern.ask
import akka.routing.CurrentRoutees
import akka.cluster.Cluster
import scala.concurrent.forkjoin.ThreadLocalRandom
import com.typesafe.config.ConfigFactory

object ClusterLoadBalancingRouterMultiJvmSpec extends MultiNodeConfig {

  class Routee extends Actor {
    var usedMemory: Array[Byte] = _
    def receive = {
      case _ ⇒ sender ! Reply(Cluster(context.system).selfAddress)
    }
  }

  class Memory extends Actor with ActorLogging {
    var usedMemory: Array[Byte] = _
    def receive = {
      case AllocateMemory ⇒
        val heap = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage
        // getMax can be undefined (-1)
        val max = math.max(heap.getMax, heap.getCommitted)
        val used = heap.getUsed
        val allocate = (0.8 * (max - used)).toInt
        usedMemory = Array.fill(allocate)(ThreadLocalRandom.current.nextInt(127).toByte)
    }
  }

  case object AllocateMemory
  case class Reply(address: Address)

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
      akka.cluster.metrics.collect-interval = 1s
      akka.cluster.metrics.gossip-interval = 1s
    """)).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterLoadBalancingRouterMultiJvmNode1 extends ClusterLoadBalancingRouterSpec
class ClusterLoadBalancingRouterMultiJvmNode2 extends ClusterLoadBalancingRouterSpec
class ClusterLoadBalancingRouterMultiJvmNode3 extends ClusterLoadBalancingRouterSpec

abstract class ClusterLoadBalancingRouterSpec extends MultiNodeSpec(ClusterLoadBalancingRouterMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import ClusterLoadBalancingRouterMultiJvmSpec._

  def currentRoutees(router: ActorRef) =
    Await.result(router ? CurrentRoutees, remaining).asInstanceOf[RouterRoutees].routees

  def receiveReplies(expectedReplies: Int): Map[Address, Int] = {
    val zero = Map.empty[Address, Int] ++ roles.map(address(_) -> 0)
    (receiveWhile(5 seconds, messages = expectedReplies) {
      case Reply(address) ⇒ address
    }).foldLeft(zero) {
      case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
    }
  }

  /**
   * Fills in self address for local ActorRef
   */
  def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  def startRouter(name: String): ActorRef = {
    val router = system.actorOf(Props[Routee].withRouter(ClusterRouterConfig(
      local = ClusterLoadBalancingRouter(HeapMetricsSelector),
      settings = ClusterRouterSettings(totalInstances = 10, maxInstancesPerNode = 1))), name)
    awaitCond {
      // it may take some time until router receives cluster member events
      currentRoutees(router).size == roles.size
    }
    currentRoutees(router).map(fullAddress).toSet must be(roles.map(address).toSet)
    router
  }

  "A cluster with a ClusterLoadBalancingRouter" must {
    "start cluster nodes" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("after-1")
    }

    "use all nodes in the cluster when not overloaded" taggedAs LongRunningTest in {
      runOn(first) {
        val router1 = startRouter("router1")

        // collect some metrics before we start
        Thread.sleep(10000)

        val iterationCount = 100
        for (i ← 0 until iterationCount) {
          router1 ! "hit"
          Thread.sleep(10)
        }

        val replies = receiveReplies(iterationCount)

        replies(first) must be > (0)
        replies(second) must be > (0)
        replies(third) must be > (0)
        replies.values.sum must be(iterationCount)

      }

      enterBarrier("after-2")
    }

    "prefer node with more free heap capacity" taggedAs LongRunningTest in {
      System.gc()
      enterBarrier("gc")

      runOn(second) {
        system.actorOf(Props[Memory], "memory") ! AllocateMemory
      }
      enterBarrier("heap-allocated")

      runOn(first) {
        val router2 = startRouter("router2")
        router2

        // collect some metrics before we start
        Thread.sleep(10000)

        val iterationCount = 100
        for (i ← 0 until iterationCount) {
          router2 ! "hit"
          Thread.sleep(10)
        }

        val replies = receiveReplies(iterationCount)

        replies(third) must be > (replies(second))
        replies.values.sum must be(iterationCount)

      }

      enterBarrier("after-3")
    }
  }
}

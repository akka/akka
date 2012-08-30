/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

import language.postfixOps
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.Address
import scala.concurrent.Await
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.Broadcast
import akka.routing.RoundRobinRouter
import akka.routing.RoutedActorRef
import akka.testkit._
import scala.concurrent.util.duration._
import akka.cluster.MultiNodeClusterSpec
import com.typesafe.config.ConfigFactory
import akka.cluster.Cluster
import akka.actor.Deploy
import akka.routing.CurrentRoutees
import akka.routing.RouterRoutees

object ClusterRoundRobinRoutedActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor extends Actor {
    def receive = {
      case "hit" ⇒ sender ! self
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.actor.deployment {
        /router1 {
          router = round-robin
          nr-of-instances = 10
          cluster.enabled = on
          cluster.max-nr-of-instances-per-node = 2
        }
      }
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterRoundRobinRoutedActorMultiJvmNode1 extends ClusterRoundRobinRoutedActorSpec
class ClusterRoundRobinRoutedActorMultiJvmNode2 extends ClusterRoundRobinRoutedActorSpec
class ClusterRoundRobinRoutedActorMultiJvmNode3 extends ClusterRoundRobinRoutedActorSpec
class ClusterRoundRobinRoutedActorMultiJvmNode4 extends ClusterRoundRobinRoutedActorSpec

abstract class ClusterRoundRobinRoutedActorSpec extends MultiNodeSpec(ClusterRoundRobinRoutedActorMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import ClusterRoundRobinRoutedActorMultiJvmSpec._

  lazy val router1 = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "router1")
  lazy val router2 = {
    import akka.cluster.routing.ClusterRouterProps
    system.actorOf(Props[SomeActor].withClusterRouter(RoundRobinRouter(),
      totalInstances = 3, maxInstancesPerNode = 1), "router2")
  }

  def receiveReplies(expectedReplies: Int): Map[Address, Int] = {
    val zero = Map.empty[Address, Int] ++ roles.map(address(_) -> 0)
    (receiveWhile(5 seconds, messages = expectedReplies) {
      case ref: ActorRef ⇒ fullAddress(ref)
    }).foldLeft(zero) {
      case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
    }
  }

  /**
   * Fills in self address for local ActorRef
   */
  private def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  "A cluster router configured with a RoundRobin router" must {
    "start cluster with 2 nodes" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      enterBarrier("after-1")
    }

    "deploy routees to the member nodes in the cluster" taggedAs LongRunningTest in {

      runOn(first) {
        router1.isInstanceOf[RoutedActorRef] must be(true)

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router1 ! "hit"
        }

        val replies = receiveReplies(iterationCount)

        replies(first) must be > (0)
        replies(second) must be > (0)
        replies(third) must be(0)
        replies(fourth) must be(0)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-2")
    }

    "deploy routees to new nodes in the cluster" taggedAs LongRunningTest in {

      // add third and fourth
      awaitClusterUp(first, second, third, fourth)

      runOn(first) {
        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router1 ! "hit"
        }

        val replies = receiveReplies(iterationCount)

        replies.values.foreach { _ must be > (0) }
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-3")
    }
  }

  "A programatically defined RoundRobin cluster router" must {
    "deploy routees to the member nodes in the cluster" taggedAs LongRunningTest in {

      runOn(first) {
        router2.isInstanceOf[RoutedActorRef] must be(true)

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router2 ! "hit"
        }

        val replies = receiveReplies(iterationCount)

        // note that router2 has totalInstances = 3, maxInstancesPerNode = 1
        val currentRoutees = Await.result(router2 ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees
        val routeeAddresses = currentRoutees map fullAddress

        routeeAddresses.size must be(3)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-4")
    }

    "deploy to other node when a node becomes down" taggedAs LongRunningTest in {

      runOn(first) {
        def currentRoutees = Await.result(router2 ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees
        def routeeAddresses = (currentRoutees map fullAddress).toSet

        val notUsedAddress = ((roles map address).toSet -- routeeAddresses).head

        val downAddress = routeeAddresses.find(_ != address(first)).get
        cluster.down(downAddress)
        awaitCond {
          routeeAddresses.contains(notUsedAddress) && !routeeAddresses.contains(downAddress)
        }

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router2 ! "hit"
        }

        val replies = receiveReplies(iterationCount)

        routeeAddresses.size must be(3)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-5")
    }
  }
}

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
        /service-hello {
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

  lazy val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")

  def receiveReplies(expectedReplies: Int): Map[Address, Int] = {
    val zero = Map.empty[Address, Int] ++ roles.map(address(_) -> 0)
    (receiveWhile(5 seconds, messages = expectedReplies) {
      case ref: ActorRef ⇒ ref.path.address match {
        case Address(_, _, None, None) ⇒ cluster.selfAddress
        case a                         ⇒ a
      }
    }).foldLeft(zero) {
      case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
    }
  }

  "A cluster router configured with a RoundRobin router" must {
    "start cluster with 2 nodes" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      enterBarrier("after-1")
    }

    "deploy routees to the member nodes in the cluster" taggedAs LongRunningTest in {

      runOn(first) {
        actor.isInstanceOf[RoutedActorRef] must be(true)

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          actor ! "hit"
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
          actor ! "hit"
        }

        val replies = receiveReplies(iterationCount)

        replies.values.foreach { _ must be > (0) }
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-3")
    }
  }
}

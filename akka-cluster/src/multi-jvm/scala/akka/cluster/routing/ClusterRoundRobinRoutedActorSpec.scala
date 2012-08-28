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
import akka.cluster.FailureDetectorPuppetStrategy
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
          nr-of-instances = 3
          cluster = on
        }
      }
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterRoundRobinRoutedActorMultiJvmNode1 extends ClusterRoundRobinRoutedActorSpec with FailureDetectorPuppetStrategy
class ClusterRoundRobinRoutedActorMultiJvmNode2 extends ClusterRoundRobinRoutedActorSpec with FailureDetectorPuppetStrategy
class ClusterRoundRobinRoutedActorMultiJvmNode3 extends ClusterRoundRobinRoutedActorSpec with FailureDetectorPuppetStrategy
class ClusterRoundRobinRoutedActorMultiJvmNode4 extends ClusterRoundRobinRoutedActorSpec with FailureDetectorPuppetStrategy

abstract class ClusterRoundRobinRoutedActorSpec extends MultiNodeSpec(ClusterRoundRobinRoutedActorMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import ClusterRoundRobinRoutedActorMultiJvmSpec._

  // sorted in the order used by the cluster
  lazy val sortedRoles = Seq(first, second, third, fourth).sorted

  // FIXME make this use of Cluster(system) more easy to use in tests
  override def cluster: Cluster = Cluster(system)

  "A cluster router configured with a RoundRobin router" must {
    "start cluster" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third, fourth)
      enterBarrier("after-1")
    }

    "be locally instantiated on a cluster node and be able to communicate through its RemoteActorRef" taggedAs LongRunningTest in {

      runOn(sortedRoles.dropRight(1): _*) {
        enterBarrier("start", "broadcast-end", "end")
      }

      runOn(sortedRoles.last) {
        enterBarrier("start")
        val actor = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "service-hello")
        actor.isInstanceOf[RoutedActorRef] must be(true)

        val connectionCount = 3
        val iterationCount = 10

        for (i ← 0 until iterationCount; k ← 0 until connectionCount) {
          actor ! "hit"
        }

        val replies: Map[Address, Int] = (receiveWhile(5 seconds, messages = connectionCount * iterationCount) {
          case ref: ActorRef ⇒ ref.path.address
        }).foldLeft(Map(node(first).address -> 0, node(second).address -> 0, node(third).address -> 0)) {
          case (replyMap, address) ⇒ replyMap + (address -> (replyMap(address) + 1))
        }

        enterBarrier("broadcast-end")
        actor ! Broadcast(PoisonPill)

        enterBarrier("end")
        replies.values foreach { _ must be(iterationCount) }
        replies.get(node(fourth).address) must be(None)

        // shut down the actor before we let the other node(s) shut down so we don't try to send
        // "Terminate" to a shut down node
        system.stop(actor)
      }

      enterBarrier("after-2")
    }
  }
}

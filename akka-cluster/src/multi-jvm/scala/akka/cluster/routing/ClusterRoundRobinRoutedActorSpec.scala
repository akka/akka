/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster.routing

import scala.concurrent.Await
import scala.concurrent.util.duration._
import com.typesafe.config.ConfigFactory

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.cluster.MultiNodeClusterSpec
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.CurrentRoutees
import akka.routing.RoundRobinRouter
import akka.routing.RoutedActorRef
import akka.routing.RouterRoutees
import akka.testkit._

object ClusterRoundRobinRoutedActorMultiJvmSpec extends MultiNodeConfig {

  class SomeActor(routeeType: RouteeType) extends Actor {
    def this() = this(DeployRoutee)

    def receive = {
      case "hit" ⇒ sender ! Reply(routeeType, self)
    }
  }

  case class Reply(routeeType: RouteeType, ref: ActorRef)

  sealed trait RouteeType extends Serializable
  object DeployRoutee extends RouteeType
  object LookupRoutee extends RouteeType

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
          cluster {
            enabled = on
            max-nr-of-instances-per-node = 2
          }
        }
        /router3 {
          router = round-robin
          nr-of-instances = 10
          cluster {
            enabled = on
            max-nr-of-instances-per-node = 1
            deploy-on-own-node = off
          }
        }
        /router4 {
          router = round-robin
          nr-of-instances = 10
          cluster {
            enabled = on
            max-nr-of-instances-per-node = 2
            routees-path = "/user/myservice"
          }
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
      totalInstances = 3, maxInstancesPerNode = 1, deployOnOwnNode = true), "router2")
  }
  lazy val router3 = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "router3")
  lazy val router4 = system.actorOf(Props[SomeActor].withRouter(RoundRobinRouter()), "router4")

  def receiveReplies(routeeType: RouteeType, expectedReplies: Int): Map[Address, Int] = {
    val zero = Map.empty[Address, Int] ++ roles.map(address(_) -> 0)
    (receiveWhile(5 seconds, messages = expectedReplies) {
      case Reply(`routeeType`, ref) ⇒ fullAddress(ref)
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

  "A cluster router with a RoundRobin router" must {
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

        val replies = receiveReplies(DeployRoutee, iterationCount)

        replies(first) must be > (0)
        replies(second) must be > (0)
        replies(third) must be(0)
        replies(fourth) must be(0)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-2")
    }

    "lookup routees on the member nodes in the cluster" taggedAs LongRunningTest in {

      // cluster consists of first and second

      system.actorOf(Props(new SomeActor(LookupRoutee)), "myservice")
      enterBarrier("myservice-started")

      runOn(first) {
        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router4 ! "hit"
        }

        val replies = receiveReplies(LookupRoutee, iterationCount)

        replies(first) must be > (0)
        replies(second) must be > (0)
        replies(third) must be(0)
        replies(fourth) must be(0)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-3")
    }

    "deploy routees to new nodes in the cluster" taggedAs LongRunningTest in {

      // add third and fourth
      awaitClusterUp(first, second, third, fourth)

      runOn(first) {
        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router1 ! "hit"
        }

        val replies = receiveReplies(DeployRoutee, iterationCount)

        replies.values.foreach { _ must be > (0) }
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-4")
    }

    "lookup routees on new nodes in the cluster" taggedAs LongRunningTest in {

      // cluster consists of first, second, third and fourth

      runOn(first) {
        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router4 ! "hit"
        }

        val replies = receiveReplies(LookupRoutee, iterationCount)

        replies.values.foreach { _ must be > (0) }
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-5")
    }

    "deploy routees to only remote nodes when deploy-on-own-node = off" taggedAs LongRunningTest in {

      runOn(first) {
        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router3 ! "hit"
        }

        val replies = receiveReplies(DeployRoutee, iterationCount)

        replies(first) must be(0)
        replies(second) must be > (0)
        replies(third) must be > (0)
        replies(fourth) must be > (0)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-6")
    }

    "deploy programatically defined routees to the member nodes in the cluster" taggedAs LongRunningTest in {

      runOn(first) {
        router2.isInstanceOf[RoutedActorRef] must be(true)

        val iterationCount = 10
        for (i ← 0 until iterationCount) {
          router2 ! "hit"
        }

        val replies = receiveReplies(DeployRoutee, iterationCount)

        // note that router2 has totalInstances = 3, maxInstancesPerNode = 1
        val currentRoutees = Await.result(router2 ? CurrentRoutees, 5 seconds).asInstanceOf[RouterRoutees].routees
        val routeeAddresses = currentRoutees map fullAddress

        routeeAddresses.size must be(3)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-7")
    }

    "deploy programatically defined routees to other node when a node becomes down" taggedAs LongRunningTest in {

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

        val replies = receiveReplies(DeployRoutee, iterationCount)

        routeeAddresses.size must be(3)
        replies.values.sum must be(iterationCount)
      }

      enterBarrier("after-8")
    }
  }
}

/**
 *  Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.routing

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.cluster.MultiNodeClusterSpec
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.Broadcast
import akka.routing.ConsistentHashingGroup
import akka.routing.ConsistentHashingRouter.ConsistentHashMapping
import akka.routing.GetRoutees
import akka.routing.Routees
import akka.testkit._

object ClusterConsistentHashingGroupMultiJvmSpec extends MultiNodeConfig {

  case object Get
  final case class Collected(messages: Set[Any])

  class Destination extends Actor {
    var receivedMessages = Set.empty[Any]
    def receive = {
      case Get ⇒ sender() ! Collected(receivedMessages)
      case m   ⇒ receivedMessages += m
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterConsistentHashingGroupMultiJvmNode1 extends ClusterConsistentHashingGroupSpec
class ClusterConsistentHashingGroupMultiJvmNode2 extends ClusterConsistentHashingGroupSpec
class ClusterConsistentHashingGroupMultiJvmNode3 extends ClusterConsistentHashingGroupSpec

abstract class ClusterConsistentHashingGroupSpec extends MultiNodeSpec(ClusterConsistentHashingGroupMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import ClusterConsistentHashingGroupMultiJvmSpec._

  /**
   * Fills in self address for local ActorRef
   */
  private def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  def currentRoutees(router: ActorRef) =
    Await.result(router ? GetRoutees, timeout.duration).asInstanceOf[Routees].routees

  "A cluster router with a consistent hashing group" must {
    "start cluster with 3 nodes" taggedAs LongRunningTest in {
      system.actorOf(Props[Destination], "dest")
      awaitClusterUp(first, second, third)
      enterBarrier("after-1")
    }

    "send to same destinations from different nodes" taggedAs LongRunningTest in {
      def hashMapping: ConsistentHashMapping = {
        case s: String ⇒ s
      }
      val paths = List("/user/dest")
      val router = system.actorOf(ClusterRouterGroup(local = ConsistentHashingGroup(paths, hashMapping = hashMapping),
        settings = ClusterRouterGroupSettings(totalInstances = 10, paths, allowLocalRoutees = true, useRole = None)).props(),
        "router")
      // it may take some time until router receives cluster member events
      awaitAssert { currentRoutees(router).size should ===(3) }
      val keys = List("A", "B", "C", "D", "E", "F", "G")
      for (_ ← 1 to 10; k ← keys) { router ! k }
      enterBarrier("messages-sent")
      router ! Broadcast(Get)
      val a = expectMsgType[Collected].messages
      val b = expectMsgType[Collected].messages
      val c = expectMsgType[Collected].messages

      a.intersect(b) should ===(Set.empty)
      a.intersect(c) should ===(Set.empty)
      b.intersect(c) should ===(Set.empty)

      (a.size + b.size + c.size) should ===(keys.size)
      enterBarrier("after-2")
    }

  }
}

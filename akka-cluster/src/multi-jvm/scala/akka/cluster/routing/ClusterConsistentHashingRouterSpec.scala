/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster.routing

import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.cluster.MultiNodeClusterSpec
import akka.pattern.ask
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.routing.ConsistentHashingRouter
import akka.routing.ConsistentHashingRouter.ConsistentHashMapping
import akka.routing.ConsistentHashingRouter.ConsistentHashableEnvelope
import akka.routing.GetRoutees
import akka.routing.FromConfig
import akka.testkit._
import akka.routing.ActorRefRoutee
import akka.routing.ConsistentHashingPool
import akka.routing.Routees

object ClusterConsistentHashingRouterMultiJvmSpec extends MultiNodeConfig {

  class Echo extends Actor {
    def receive = {
      case _ ⇒ sender() ! self
    }
  }

  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString(s"""
      common-router-settings = {
        router = consistent-hashing-pool
        cluster {
          enabled = on
          max-nr-of-instances-per-node = 2
          max-total-nr-of-instances = 10
        }
      }

      akka.actor.deployment {
        /router1 = $${common-router-settings}
        /router3 = $${common-router-settings}
        /router4 = $${common-router-settings}
      }
      """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))

}

class ClusterConsistentHashingRouterMultiJvmNode1 extends ClusterConsistentHashingRouterSpec
class ClusterConsistentHashingRouterMultiJvmNode2 extends ClusterConsistentHashingRouterSpec
class ClusterConsistentHashingRouterMultiJvmNode3 extends ClusterConsistentHashingRouterSpec

abstract class ClusterConsistentHashingRouterSpec extends MultiNodeSpec(ClusterConsistentHashingRouterMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with DefaultTimeout {
  import ClusterConsistentHashingRouterMultiJvmSpec._

  lazy val router1 = system.actorOf(FromConfig.props(Props[Echo]), "router1")

  def currentRoutees(router: ActorRef) =
    Await.result(router ? GetRoutees, timeout.duration).asInstanceOf[Routees].routees

  /**
   * Fills in self address for local ActorRef
   */
  private def fullAddress(actorRef: ActorRef): Address = actorRef.path.address match {
    case Address(_, _, None, None) ⇒ cluster.selfAddress
    case a                         ⇒ a
  }

  "A cluster router with a consistent hashing pool" must {
    "start cluster with 2 nodes" taggedAs LongRunningTest in {
      awaitClusterUp(first, second)
      enterBarrier("after-1")
    }

    "create routees from configuration" in {
      runOn(first) {
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router1).size should ===(4) }
        val routees = currentRoutees(router1)
        routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet should ===(Set(address(first), address(second)))
      }
      enterBarrier("after-2")
    }

    "select destination based on hashKey" in {
      runOn(first) {
        router1 ! ConsistentHashableEnvelope(message = "A", hashKey = "a")
        val destinationA = expectMsgType[ActorRef]
        router1 ! ConsistentHashableEnvelope(message = "AA", hashKey = "a")
        expectMsg(destinationA)
      }
      enterBarrier("after-2")
    }

    "deploy routees to new member nodes in the cluster" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first) {
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router1).size should ===(6) }
        val routees = currentRoutees(router1)
        routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet should ===(roles.map(address).toSet)
      }

      enterBarrier("after-3")
    }

    "deploy programatically defined routees to the member nodes in the cluster" taggedAs LongRunningTest in {
      runOn(first) {
        val router2 = system.actorOf(ClusterRouterPool(local = ConsistentHashingPool(nrOfInstances = 0),
          settings = ClusterRouterPoolSettings(totalInstances = 10, maxInstancesPerNode = 2, allowLocalRoutees = true, useRole = None)).
          props(Props[Echo]),
          "router2")
        // it may take some time until router receives cluster member events
        awaitAssert { currentRoutees(router2).size should ===(6) }
        val routees = currentRoutees(router2)
        routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet should ===(roles.map(address).toSet)
      }

      enterBarrier("after-4")
    }

    "handle combination of configured router and programatically defined hashMapping" taggedAs LongRunningTest in {
      runOn(first) {
        def hashMapping: ConsistentHashMapping = {
          case s: String ⇒ s
        }

        val router3 = system.actorOf(ConsistentHashingPool(nrOfInstances = 0, hashMapping = hashMapping).props(Props[Echo]), "router3")

        assertHashMapping(router3)
      }

      enterBarrier("after-5")
    }

    "handle combination of configured router and programatically defined hashMapping and ClusterRouterConfig" taggedAs LongRunningTest in {
      runOn(first) {
        def hashMapping: ConsistentHashMapping = {
          case s: String ⇒ s
        }

        val router4 = system.actorOf(ClusterRouterPool(
          local = ConsistentHashingPool(nrOfInstances = 0, hashMapping = hashMapping),
          settings = ClusterRouterPoolSettings(totalInstances = 10, maxInstancesPerNode = 1, allowLocalRoutees = true, useRole = None)).
          props(Props[Echo]),
          "router4")

        assertHashMapping(router4)
      }

      enterBarrier("after-6")
    }

    def assertHashMapping(router: ActorRef): Unit = {
      // it may take some time until router receives cluster member events
      awaitAssert { currentRoutees(router).size should ===(6) }
      val routees = currentRoutees(router)
      routees.map { case ActorRefRoutee(ref) ⇒ fullAddress(ref) }.toSet should ===(roles.map(address).toSet)

      router ! "a"
      val destinationA = expectMsgType[ActorRef]
      router ! "a"
      expectMsg(destinationA)
    }

  }
}

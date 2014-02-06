/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.testkit._
import akka.actor._
import akka.routing._
import com.typesafe.config._
import akka.cluster.routing.ClusterRouterPool
import akka.cluster.routing.ClusterRouterGroup
import akka.cluster.routing.ClusterRouterPoolSettings
import akka.cluster.routing.ClusterRouterGroupSettings

object ClusterDeployerSpec {
  val deployerConf = ConfigFactory.parseString("""
      akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      akka.actor.deployment {
        /user/service1 {
          router = round-robin
          nr-of-instances = 20
          cluster.enabled = on
          cluster.max-nr-of-instances-per-node = 3
          cluster.allow-local-routees = off
        }
        /user/service2 {
          dispatcher = mydispatcher
          mailbox = mymailbox
          router = round-robin
          nr-of-instances = 20
          routees.paths = ["/user/myservice"]
          cluster.enabled = on
          cluster.allow-local-routees = off
        }
        # deprecated cluster.routees-path
        /user/service3 {
          dispatcher = mydispatcher
          mailbox = mymailbox
          router = round-robin
          nr-of-instances = 20
          cluster.enabled = on
          cluster.allow-local-routees = off
          cluster.routees-path = "/user/myservice"
        }
      }
      akka.remote.netty.tcp.port = 0
      """, ConfigParseOptions.defaults)

  class RecipeActor extends Actor {
    def receive = { case _ ⇒ }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterDeployerSpec extends AkkaSpec(ClusterDeployerSpec.deployerConf) {

  "A RemoteDeployer" must {

    "be able to parse 'akka.actor.deployment._' with specified cluster pool" in {
      val service = "/user/service1"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment should not be (None)

      deployment should be(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterPool(RoundRobinPool(20), ClusterRouterPoolSettings(
            totalInstances = 20, maxInstancesPerNode = 3, allowLocalRoutees = false, useRole = None)),
          ClusterScope,
          Deploy.NoDispatcherGiven,
          Deploy.NoMailboxGiven)))
    }

    "be able to parse 'akka.actor.deployment._' with specified cluster group" in {
      val service = "/user/service2"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment should not be (None)

      deployment should be(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterGroup(RoundRobinGroup(List("/user/myservice")), ClusterRouterGroupSettings(
            totalInstances = 20, routeesPaths = List("/user/myservice"), allowLocalRoutees = false, useRole = None)),
          ClusterScope,
          "mydispatcher",
          "mymailbox")))
    }

    "be able to parse 'akka.actor.deployment._' with deprecated 'cluster.routees-path'" in {
      val service = "/user/service3"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment should not be (None)

      deployment should be(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterGroup(RoundRobinGroup(List("/user/myservice")), ClusterRouterGroupSettings(
            totalInstances = 20, routeesPaths = List("/user/myservice"), allowLocalRoutees = false, useRole = None)),
          ClusterScope,
          "mydispatcher",
          "mymailbox")))
    }

    "have correct router mappings" in {
      val mapping = system.asInstanceOf[ActorSystemImpl].provider.deployer.routerTypeMapping
      mapping("adaptive-pool") should be(classOf[akka.cluster.routing.AdaptiveLoadBalancingPool].getName)
      mapping("adaptive-group") should be(classOf[akka.cluster.routing.AdaptiveLoadBalancingGroup].getName)
    }

  }

}

/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
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
      akka.actor.provider = "cluster"
      akka.actor.deployment {
        /user/service1 {
          router = round-robin-pool
          cluster.enabled = on
          cluster.max-nr-of-instances-per-node = 3
          cluster.max-total-nr-of-instances = 20
          cluster.allow-local-routees = off
        }
        /user/service2 {
          dispatcher = mydispatcher
          mailbox = mymailbox
          router = round-robin-group
          routees.paths = ["/user/myservice"]
          cluster.enabled = on
          cluster.max-total-nr-of-instances = 20
          cluster.allow-local-routees = off
        }
      }
      akka.remote.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      """, ConfigParseOptions.defaults)

  class RecipeActor extends Actor {
    def receive = { case _ ⇒ }
  }

}

class ClusterDeployerSpec extends AkkaSpec(ClusterDeployerSpec.deployerConf) {

  "A RemoteDeployer" must {

    "be able to parse 'akka.actor.deployment._' with specified cluster pool" in {
      val service = "/user/service1"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment should not be (None)

      deployment should ===(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterPool(RoundRobinPool(20), ClusterRouterPoolSettings(
            totalInstances = 20, maxInstancesPerNode = 3, allowLocalRoutees = false, useRoleSet = Set.empty)),
          ClusterScope,
          Deploy.NoDispatcherGiven,
          Deploy.NoMailboxGiven)))
    }

    "be able to parse 'akka.actor.deployment._' with specified cluster group" in {
      val service = "/user/service2"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment should not be (None)

      deployment should ===(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterGroup(RoundRobinGroup(List("/user/myservice")), ClusterRouterGroupSettings(
            totalInstances = 20, routeesPaths = List("/user/myservice"), allowLocalRoutees = false, useRoleSet = Set.empty)),
          ClusterScope,
          "mydispatcher",
          "mymailbox")))
    }

  }

}

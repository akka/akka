/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import akka.testkit._
import akka.actor._
import akka.routing._
import com.typesafe.config._
import akka.cluster.routing.ClusterRouterConfig
import akka.cluster.routing.ClusterRouterSettings

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

    "be able to parse 'akka.actor.deployment._' with specified cluster lookup routee settings" in {
      val service = "/user/service1"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment must not be (None)

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterConfig(RoundRobinRouter(20), ClusterRouterSettings(
            totalInstances = 20, maxInstancesPerNode = 3, allowLocalRoutees = false, useRole = None)),
          ClusterScope)))
    }

    "be able to parse 'akka.actor.deployment._' with specified cluster deploy routee settings" in {
      val service = "/user/service2"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment must not be (None)

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          ClusterRouterConfig(RoundRobinRouter(20), ClusterRouterSettings(
            totalInstances = 20, routeesPath = "/user/myservice", allowLocalRoutees = false, useRole = None)),
          ClusterScope)))
    }

  }

}

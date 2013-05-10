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
import scala.concurrent.duration._

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
          cluster.enabled = on
          cluster.allow-local-routees = off
          cluster.routees-path = "/user/myservice"
          cluster.retry-lookup-interval = 3s
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

      val d = deployment.get
      d.path must be(service)
      d.scope must be(ClusterScope)
      d.dispatcher must be(Deploy.NoDispatcherGiven)
      d.mailbox must be(Deploy.NoMailboxGiven)
      d.routerConfig match {
        case c: ClusterRouterConfig ⇒
          c.local must be(RoundRobinRouter(20))
          c.settings.totalInstances must be(20)
          c.settings.maxInstancesPerNode must be(3)
          c.settings.allowLocalRoutees must be(false)
          c.settings.routeesPath must be("")
          c.settings.useRole must be(None)
          c.settings.retryLookupInterval.isFinite must be(false) // Duration.Undefined
        case _ ⇒ fail("unexpected routerConfig: " + d.routerConfig)
      }
    }

    "be able to parse 'akka.actor.deployment._' with specified cluster deploy routee settings" in {
      val service = "/user/service2"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment must not be (None)

      val d = deployment.get
      d.path must be(service)
      d.scope must be(ClusterScope)
      d.dispatcher must be("mydispatcher")
      d.mailbox must be("mymailbox")
      d.routerConfig match {
        case c: ClusterRouterConfig ⇒
          c.local must be(RoundRobinRouter(20))
          c.settings.totalInstances must be(20)
          c.settings.maxInstancesPerNode must be(1)
          c.settings.allowLocalRoutees must be(false)
          c.settings.routeesPath must be("/user/myservice")
          c.settings.useRole must be(None)
          c.settings.retryLookupInterval must be(3.seconds)
        case _ ⇒ fail("unexpected routerConfig: " + d.routerConfig)
      }

    }

  }

}

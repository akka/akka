/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import akka.routing._
import com.typesafe.config._

object RemoteDeployerSpec {
  val deployerConf = ConfigFactory.parseString("""
      akka.actor.provider = "akka.remote.RemoteActorRefProvider"
      akka.actor.deployment {
        /user/service2 {
          router = round-robin
          nr-of-instances = 3
          remote = "akka://sys@wallace:2552"
        }
      }
      akka.remote.netty.port = 0
      """, ConfigParseOptions.defaults)

  class RecipeActor extends Actor {
    def receive = { case _ ⇒ }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RemoteDeployerSpec extends AkkaSpec(RemoteDeployerSpec.deployerConf) {

  "A RemoteDeployer" must {

    "be able to parse 'akka.actor.deployment._' with specified remote nodes" in {
      val service = "/user/service2"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookup(service.split("/").drop(1))
      deployment must be('defined)

      deployment must be(Some(
        Deploy(
          service,
          deployment.get.config,
          RoundRobinRouter(3),
          RemoteScope(Address("akka", "sys", "wallace", 2552)))))
    }

  }

}
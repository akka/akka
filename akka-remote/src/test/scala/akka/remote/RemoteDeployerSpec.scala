/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.testkit._
import akka.actor._
import com.typesafe.config._
import akka.actor.DeploymentConfig._
import akka.remote.RemoteDeploymentConfig.RemoteScope

object RemoteDeployerSpec {
  val deployerConf = ConfigFactory.parseString("""
      akka.actor.provider = "akka.remote.RemoteActorRefProvider"
      akka.cluster.nodename = Whatever
      akka.actor.deployment {
        /user/service1 {
        }
        /user/service2 {
          router = round-robin
          nr-of-instances = 3
          remote {
            nodes = ["wallace:2552", "gromit:2552"]
          }
        }
        /user/service3 {
          create-as {
            class = "akka.actor.DeployerSpec$RecipeActor"
          }
        }
        /user/service-auto {
          router = round-robin
          nr-of-instances = auto
        }
        /user/service-direct {
          router = direct
        }
        /user/service-direct2 {
          router = direct
          # nr-of-instances ignored when router = direct
          nr-of-instances = 2
        }
        /user/service-round-robin {
          router = round-robin
        }
        /user/service-random {
          router = random
        }
        /user/service-scatter-gather {
          router = scatter-gather
        }
        /user/service-least-cpu {
          router = least-cpu
        }
        /user/service-least-ram {
          router = least-ram
        }
        /user/service-least-messages {
          router = least-messages
        }
        /user/service-custom {
          router = org.my.Custom
        }
        /user/service-cluster1 {
          cluster {
            preferred-nodes = ["node:wallace", "node:gromit"]
          }
        }
        /user/service-cluster2 {
          cluster {
            preferred-nodes = ["node:wallace", "node:gromit"]
            replication {
              strategy = write-behind
            }
          }
        }
      }
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
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment must be(Some(
        Deploy(
          service,
          None,
          RoundRobin,
          NrOfInstances(3),
          RemoteScope(Seq(
            RemoteAddress(system.name, "wallace", 2552), RemoteAddress(system.name, "gromit", 2552))))))
    }

  }

}
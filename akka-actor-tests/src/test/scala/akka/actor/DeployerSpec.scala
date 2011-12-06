/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit.AkkaSpec
import akka.util.duration._
import DeploymentConfig._
import akka.remote.RemoteAddress
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions

object DeployerSpec {
  val deployerConf = ConfigFactory.parseString("""
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
class DeployerSpec extends AkkaSpec(DeployerSpec.deployerConf) {

  "A Deployer" must {

    "be able to parse 'akka.actor.deployment._' with all default values" in {
      val service = "/user/service1"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment must be(Some(
        Deploy(
          service,
          None,
          Direct,
          NrOfInstances(1),
          LocalScope)))
    }

    "use None deployment for undefined service" in {
      val service = "/user/undefined"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be(None)
    }

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

    "be able to parse 'akka.actor.deployment._' with recipe" in {
      val service = "/user/service3"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment must be(Some(
        Deploy(
          service,
          Some(ActorRecipe(classOf[DeployerSpec.RecipeActor])),
          Direct,
          NrOfInstances(1),
          LocalScope)))
    }

    "be able to parse 'akka.actor.deployment._' with number-of-instances=auto" in {
      val service = "/user/service-auto"
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment must be(Some(
        Deploy(
          service,
          None,
          RoundRobin,
          AutoNrOfInstances,
          LocalScope)))
    }

    "detect invalid number-of-instances" in {
      intercept[akka.config.ConfigurationException] {
        val invalidDeployerConf = ConfigFactory.parseString("""
            akka.actor.deployment {
              /user/service-invalid-number-of-instances {
                router = round-robin
                nr-of-instances = boom
              }
            }
            """, ConfigParseOptions.defaults).withFallback(AkkaSpec.testConf)

        ActorSystem("invalid", invalidDeployerConf).stop()
      }
    }

    "be able to parse 'akka.actor.deployment._' with direct router" in {
      assertRouting(Direct, "/user/service-direct")
    }

    "ignore nr-of-instances with direct router" in {
      assertRouting(Direct, "/user/service-direct2")
    }

    "be able to parse 'akka.actor.deployment._' with round-robin router" in {
      assertRouting(RoundRobin, "/user/service-round-robin")
    }

    "be able to parse 'akka.actor.deployment._' with random router" in {
      assertRouting(Random, "/user/service-random")
    }

    "be able to parse 'akka.actor.deployment._' with scatter-gather router" in {
      assertRouting(ScatterGather, "/user/service-scatter-gather")
    }

    "be able to parse 'akka.actor.deployment._' with least-cpu router" in {
      assertRouting(LeastCPU, "/user/service-least-cpu")
    }

    "be able to parse 'akka.actor.deployment._' with least-ram router" in {
      assertRouting(LeastRAM, "/user/service-least-ram")
    }

    "be able to parse 'akka.actor.deployment._' with least-messages router" in {
      assertRouting(LeastMessages, "/user/service-least-messages")
    }
    "be able to parse 'akka.actor.deployment._' with custom router" in {
      assertRouting(CustomRouter("org.my.Custom"), "/user/service-custom")
    }

    def assertRouting(expected: Routing, service: String) {
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment must be(Some(
        Deploy(
          service,
          None,
          expected,
          NrOfInstances(1),
          LocalScope)))

    }

    "be able to parse 'akka.actor.deployment._' with specified cluster nodes" in {
      val service = "/user/service-cluster1"
      val deploymentConfig = system.asInstanceOf[ActorSystemImpl].provider.deployer.deploymentConfig
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment.get.scope match {
        case deploymentConfig.ClusterScope(remoteNodes, replication) ⇒
          remoteNodes must be(Seq(Node("wallace"), Node("gromit")))
          replication must be(Transient)
        case other ⇒ fail("Unexpected: " + other)
      }
    }

    "be able to parse 'akka.actor.deployment._' with specified cluster replication" in {
      val service = "/user/service-cluster2"
      val deploymentConfig = system.asInstanceOf[ActorSystemImpl].provider.deployer.deploymentConfig
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupDeployment(service)
      deployment must be('defined)

      deployment.get.scope match {
        case deploymentConfig.ClusterScope(remoteNodes, Replication(storage, strategy)) ⇒
          storage must be(TransactionLog)
          strategy must be(WriteBehind)
        case other ⇒ fail("Unexpected: " + other)
      }
    }

  }
}

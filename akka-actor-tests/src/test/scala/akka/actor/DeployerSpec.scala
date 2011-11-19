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
        /app/service-ping {
          nr-of-instances = 3
          router = "round-robin"
          remote {
            nodes = ["wallace:2552", "gromit:2552"]
          }
        }
      }
      """, ConfigParseOptions.defaults)

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DeployerSpec extends AkkaSpec(DeployerSpec.deployerConf) {

  "A Deployer" must {

    "be able to parse 'akka.actor.deployment._' config elements" in {
      val p = system.settings.config.getString("akka.actor.provider")
      val deployment = system.asInstanceOf[ActorSystemImpl].provider.deployer.lookupInConfig("/app/service-ping")
      deployment must be('defined)

      deployment must equal(Some(
        Deploy(
          "/app/service-ping",
          None,
          RoundRobin,
          NrOfInstances(3),
          RemoteScope(List(
            RemoteAddress("wallace", 2552), RemoteAddress("gromit", 2552))))))
      // ClusterScope(
      //   List(Node("node1")),
      //   new NrOfInstances(3),
      //   Replication(
      //     TransactionLog,
      //     WriteThrough)))))
    }
  }
}

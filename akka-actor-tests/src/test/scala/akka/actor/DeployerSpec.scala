/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit.AkkaSpec
import akka.util.duration._
import DeploymentConfig._

class DeployerSpec extends AkkaSpec {

  "A Deployer" must {
    "be able to parse 'akka.actor.deployment._' config elements" in {
      val deployment = app.deployer.lookupInConfig("service-ping")
      deployment must be('defined)

      deployment must equal(Some(
        Deploy(
          "service-ping",
          None,
          RoundRobin,
          NrOfInstances(3),
          BannagePeriodFailureDetector(10 seconds),
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

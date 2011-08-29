/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import DeploymentConfig._

class DeployerSpec extends WordSpec with MustMatchers {

  "A Deployer" must {
    "be able to parse 'akka.actor.deployment._' config elements" in {
      val deployment = Deployer.lookupInConfig("service-ping")
      deployment must be('defined)

      deployment must equal(Some(
        Deploy(
          "service-ping",
          None,
          LeastCPU,
          Clustered(
            Vector(Node("node1")),
            new ReplicationFactor(3),
            Replication(
              TransactionLog,
              WriteThrough)))))
    }
  }
}

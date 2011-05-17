/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import DeploymentConfig._

class DeployerSpec extends WordSpec with MustMatchers {

  "A Deployer" must {
    "be able to parse 'akka.actor.deployment._' config elements" in {
      val deployment = Deployer.lookupInConfig("service-pi")
      deployment must be ('defined)
      deployment must equal (Some(Deploy(
          "service-pi",
          RoundRobin,
          "akka.serialization.Format$Default$",
          Clustered(
              Node("test-1"),
              Replicate(3),
              Stateless))))
    }
  }
}

Deployer.deploy(Deploy(
  "service-pi",
  RoundRobin,
  "akka.serialization.Format$Default$",
  Clustered(
    Node("test-1"),
    Replicate(3),
    Stateless))))

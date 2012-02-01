/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import scala.util.duration._
import scala.util.Duration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterConfigSpec extends AkkaSpec(
  """
  akka {
    actor {
      provider = "akka.remote.RemoteActorRefProvider"
    }
  }
  """) {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      FailureDetectorThreshold must be(8)
      FailureDetectorMaxSampleSize must be(1000)
      SeedNodeConnectionTimeout must be(30 seconds)
      MaxTimeToRetryJoiningCluster must be(30 seconds)
      InitialDelayForGossip must be(5 seconds)
      GossipFrequency must be(1 second)
      SeedNodes must be(Set())
    }
  }
}

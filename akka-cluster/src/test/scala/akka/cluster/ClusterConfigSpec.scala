/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import akka.util.duration._
import akka.util.Duration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterConfigSpec extends ClusterSpec {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      FailureDetectorThreshold must be(8)
      FailureDetectorMaxSampleSize must be(1000)
      NodeToJoin must be(None)
      GossipInitialDelay must be(5 seconds)
      GossipFrequency must be(1 second)
      NrOfGossipDaemons must be(4)
      NrOfDeputyNodes must be(3)
      AutoDown must be(true)
    }
  }
}

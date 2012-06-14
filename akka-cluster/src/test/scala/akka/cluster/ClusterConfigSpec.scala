/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.AkkaSpec
import akka.util.duration._
import akka.util.Duration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterConfigSpec extends AkkaSpec {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      FailureDetectorThreshold must be(8)
      FailureDetectorMaxSampleSize must be(1000)
      FailureDetectorImplementationClass must be(None)
      NodeToJoin must be(None)
      PeriodicTasksInitialDelay must be(1 seconds)
      GossipInterval must be(1 second)
      LeaderActionsInterval must be(1 second)
      UnreachableNodesReaperInterval must be(1 second)
      NrOfGossipDaemons must be(4)
      NrOfDeputyNodes must be(3)
      AutoDown must be(true)
    }
  }
}

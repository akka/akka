/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import akka.testkit.AkkaSpec
import akka.dispatch.Dispatchers
import scala.concurrent.duration._
import akka.remote.PhiAccrualFailureDetector
import akka.util.Helpers.ConfigOps

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterConfigSpec extends AkkaSpec {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      LogInfo should be(true)
      FailureDetectorConfig.getDouble("threshold") should be(8.0 +- 0.0001)
      FailureDetectorConfig.getInt("max-sample-size") should be(1000)
      FailureDetectorConfig.getMillisDuration("min-std-deviation") should be(100 millis)
      FailureDetectorConfig.getMillisDuration("acceptable-heartbeat-pause") should be(3 seconds)
      FailureDetectorImplementationClass should be(classOf[PhiAccrualFailureDetector].getName)
      SeedNodes should be(Seq.empty[String])
      SeedNodeTimeout should be(5 seconds)
      RetryUnsuccessfulJoinAfter should be(10 seconds)
      PeriodicTasksInitialDelay should be(1 seconds)
      GossipInterval should be(1 second)
      GossipTimeToLive should be(2 seconds)
      HeartbeatInterval should be(1 second)
      MonitoredByNrOfMembers should be(5)
      HeartbeatExpectedResponseAfter should be(5 seconds)
      LeaderActionsInterval should be(1 second)
      UnreachableNodesReaperInterval should be(1 second)
      PublishStatsInterval should be(Duration.Undefined)
      AutoDownUnreachableAfter should be(Duration.Undefined)
      MinNrOfMembers should be(1)
      MinNrOfMembersOfRole should be(Map.empty)
      Roles should be(Set.empty)
      JmxEnabled should be(true)
      UseDispatcher should be(Dispatchers.DefaultDispatcherId)
      GossipDifferentViewProbability should be(0.8 +- 0.0001)
      ReduceGossipDifferentViewProbability should be(400)
      SchedulerTickDuration should be(33 millis)
      SchedulerTicksPerWheel should be(512)
      MetricsEnabled should be(true)
      MetricsCollectorClass should be(classOf[SigarMetricsCollector].getName)
      MetricsInterval should be(3 seconds)
      MetricsGossipInterval should be(3 seconds)
      MetricsMovingAverageHalfLife should be(12 seconds)
    }
  }
}

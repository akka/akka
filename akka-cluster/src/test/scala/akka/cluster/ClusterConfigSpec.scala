/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps
import akka.testkit.AkkaSpec
import akka.dispatch.Dispatchers
import scala.concurrent.duration._
import akka.remote.PhiAccrualFailureDetector

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterConfigSpec extends AkkaSpec {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      FailureDetectorConfig.getDouble("threshold") must be(8.0 plusOrMinus 0.0001)
      FailureDetectorConfig.getInt("max-sample-size") must be(1000)
      Duration(FailureDetectorConfig.getMilliseconds("min-std-deviation"), MILLISECONDS) must be(100 millis)
      Duration(FailureDetectorConfig.getMilliseconds("acceptable-heartbeat-pause"), MILLISECONDS) must be(3 seconds)
      FailureDetectorImplementationClass must be(classOf[PhiAccrualFailureDetector].getName)
      SeedNodes must be(Seq.empty[String])
      SeedNodeTimeout must be(5 seconds)
      RetryUnsuccessfulJoinAfter must be(10 seconds)
      PeriodicTasksInitialDelay must be(1 seconds)
      GossipInterval must be(1 second)
      HeartbeatInterval must be(1 second)
      NumberOfEndHeartbeats must be(8)
      MonitoredByNrOfMembers must be(5)
      HeartbeatRequestDelay must be(10 seconds)
      HeartbeatExpectedResponseAfter must be(3 seconds)
      HeartbeatRequestTimeToLive must be(1 minute)
      LeaderActionsInterval must be(1 second)
      UnreachableNodesReaperInterval must be(1 second)
      PublishStatsInterval must be(10 second)
      AutoJoin must be(true)
      AutoDown must be(false)
      MinNrOfMembers must be(1)
      MinNrOfMembersOfRole must be === Map.empty
      Roles must be === Set.empty
      JmxEnabled must be(true)
      UseDispatcher must be(Dispatchers.DefaultDispatcherId)
      GossipDifferentViewProbability must be(0.8 plusOrMinus 0.0001)
      SchedulerTickDuration must be(33 millis)
      SchedulerTicksPerWheel must be(512)
      MetricsEnabled must be(true)
      MetricsCollectorClass must be(classOf[SigarMetricsCollector].getName)
      MetricsInterval must be(3 seconds)
      MetricsGossipInterval must be(3 seconds)
      MetricsMovingAverageHalfLife must be(12 seconds)
    }
  }
}

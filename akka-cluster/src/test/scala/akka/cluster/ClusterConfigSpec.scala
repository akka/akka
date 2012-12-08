/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.postfixOps

import akka.testkit.AkkaSpec
import akka.dispatch.Dispatchers
import scala.concurrent.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterConfigSpec extends AkkaSpec {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      FailureDetectorThreshold must be(8.0 plusOrMinus 0.0001)
      FailureDetectorMaxSampleSize must be(1000)
      FailureDetectorImplementationClass must be(classOf[AccrualFailureDetector].getName)
      FailureDetectorMinStdDeviation must be(100 millis)
      FailureDetectorAcceptableHeartbeatPause must be(3 seconds)
      SeedNodes must be(Seq.empty[String])
      SeedNodeTimeout must be(5 seconds)
      PeriodicTasksInitialDelay must be(1 seconds)
      GossipInterval must be(1 second)
      HeartbeatInterval must be(1 second)
      NumberOfEndHeartbeats must be(4)
      MonitoredByNrOfMembers must be(5)
      LeaderActionsInterval must be(1 second)
      UnreachableNodesReaperInterval must be(1 second)
      PublishStatsInterval must be(10 second)
      JoinTimeout must be(60 seconds)
      AutoJoin must be(true)
      AutoDown must be(false)
      JmxEnabled must be(true)
      UseDispatcher must be(Dispatchers.DefaultDispatcherId)
      GossipDifferentViewProbability must be(0.8 plusOrMinus 0.0001)
      MaxGossipMergeRate must be(5.0 plusOrMinus 0.0001)
      SchedulerTickDuration must be(33 millis)
      SchedulerTicksPerWheel must be(512)
      SendCircuitBreakerSettings must be(CircuitBreakerSettings(
        maxFailures = 3,
        callTimeout = 2 seconds,
        resetTimeout = 30 seconds))
      MetricsEnabled must be(true)
      MetricsCollectorClass must be(classOf[SigarMetricsCollector].getName)
      MetricsInterval must be(3 seconds)
      MetricsGossipInterval must be(3 seconds)
      MetricsMovingAverageHalfLife must be(12 seconds)
    }
  }
}

/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import language.postfixOps
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import akka.testkit.AkkaSpec
import akka.dispatch.Dispatchers

import akka.remote.PhiAccrualFailureDetector
import akka.util.Helpers.ConfigOps
import akka.actor.Address

class ClusterConfigSpec extends AkkaSpec {

  "Clustering" must {

    "be able to parse generic cluster config elements" in {
      val settings = new ClusterSettings(system.settings.config, system.name)
      import settings._
      LogInfo should ===(true)
      FailureDetectorConfig.getDouble("threshold") should ===(8.0 +- 0.0001)
      FailureDetectorConfig.getInt("max-sample-size") should ===(1000)
      FailureDetectorConfig.getMillisDuration("min-std-deviation") should ===(100 millis)
      FailureDetectorConfig.getMillisDuration("acceptable-heartbeat-pause") should ===(3 seconds)
      FailureDetectorImplementationClass should ===(classOf[PhiAccrualFailureDetector].getName)
      SeedNodes should ===(Vector.empty[Address])
      SeedNodeTimeout should ===(5 seconds)
      RetryUnsuccessfulJoinAfter should ===(10 seconds)
      PeriodicTasksInitialDelay should ===(1 seconds)
      GossipInterval should ===(1 second)
      GossipTimeToLive should ===(2 seconds)
      HeartbeatInterval should ===(1 second)
      MonitoredByNrOfMembers should ===(5)
      HeartbeatExpectedResponseAfter should ===(1 seconds)
      LeaderActionsInterval should ===(1 second)
      UnreachableNodesReaperInterval should ===(1 second)
      PublishStatsInterval should ===(Duration.Undefined)
      AutoDownUnreachableAfter should ===(Duration.Undefined)
      DownRemovalMargin should ===(Duration.Zero)
      MinNrOfMembers should ===(1)
      MinNrOfMembersOfRole should ===(Map.empty[String, Int])
      Team should ===("default")
      Roles should ===(Set(ClusterSettings.TeamRolePrefix + "default"))
      JmxEnabled should ===(true)
      UseDispatcher should ===(Dispatchers.DefaultDispatcherId)
      GossipDifferentViewProbability should ===(0.8 +- 0.0001)
      ReduceGossipDifferentViewProbability should ===(400)
      SchedulerTickDuration should ===(33 millis)
      SchedulerTicksPerWheel should ===(512)
    }

    "be able to parse non-default cluster config elements" in {
      val settings = new ClusterSettings(ConfigFactory.parseString(
        """
          |akka {
          |  cluster {
          |    roles = [ "hamlet" ]
          |    team = "blue"
          |  }
          |}
        """.stripMargin).withFallback(ConfigFactory.load()), system.name)
      import settings._
      Roles should ===(Set("hamlet", ClusterSettings.TeamRolePrefix + "blue"))
      Team should ===("blue")
    }
  }
}

/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics

import language.postfixOps
import akka.testkit.AkkaSpec
import akka.dispatch.Dispatchers
import scala.concurrent.duration._
import akka.remote.PhiAccrualFailureDetector
import akka.util.Helpers.ConfigOps
import com.typesafe.config.ConfigFactory

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterMetricsSettingsSpec extends AkkaSpec {

  "ClusterMetricsSettings" must {

    "be able to parse generic metrics config elements" in {
      val settings = new ClusterMetricsSettings(system.settings.config)
      import settings._

      // Extension.
      MetricsDispatcher should be(Dispatchers.DefaultDispatcherId)
      PeriodicTasksInitialDelay should be(1 second)
      NativeLibraryExtractFolder should be(System.getProperty("user.dir") + "/native")
      SerializerIdentifier should be(10)

      // Supervisor.
      SupervisorName should be("cluster-metrics")
      SupervisorStrategyProvider should be(classOf[ClusterMetricsStrategy].getName)
      SupervisorStrategyConfiguration should be(
        ConfigFactory.parseString("loggingEnabled=true,maxNrOfRetries=3,withinTimeRange=3s"))

      // Collector.
      CollectorEnabled should be(true)
      CollectorProvider should be("")
      CollectorSampleInterval should be(3 seconds)
      CollectorGossipInterval should be(3 seconds)
      CollectorMovingAverageHalfLife should be(12 seconds)
    }
  }
}

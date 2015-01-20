/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics

import scala.language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{ Success, Try, Failure }
import akka.actor._
import akka.testkit._
import akka.cluster.metrics.StandardMetrics._
import org.scalatest.WordSpec
import org.scalatest.Matchers
import akka.cluster.Cluster

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsExtensionSpec extends AkkaSpec(MetricsConfig.clusterSigarMock)
  with ImplicitSender with RedirectLogging {
  import system.dispatcher

  val cluster = Cluster(system)

  val extension = ClusterMetricsExtension(system)

  val metricsView = new ClusterMetricsView(cluster.system)

  val sampleInterval = extension.settings.CollectorSampleInterval

  def metricsNodeCount = metricsView.clusterMetrics.size

  def metricsHistorySize = metricsView.metricsHistory.size

  // This is a single node test.
  val nodeCount = 1

  // Limit collector sample count.
  val sampleCount = 10

  // Metrics verification precision.
  val epsilon = 0.001

  // Sleep longer then single sample.
  def awaitSample(time: Long = 3 * sampleInterval.toMillis) = Thread.sleep(time)

  "Metrics Extension" must {

    "collect metrics after start command" in {
      extension.supervisor ! CollectionStartMessage
      awaitAssert(metricsNodeCount should be(nodeCount), 15 seconds)
    }

    "collect mock sample during a time window" in {
      awaitAssert(metricsHistorySize should be(sampleCount), 15 seconds)
      extension.supervisor ! CollectionStopMessage
      awaitSample()
      metricsNodeCount should be(nodeCount)
      metricsHistorySize should be >= (sampleCount)
    }

    "verify sigar mock data matches expected ewma data" in {

      val history = metricsView.metricsHistory.reverse.map { _.head }

      val expected = List(
        (0.700, 0.000, 0.000),
        (0.700, 0.018, 0.007),
        (0.700, 0.051, 0.020),
        (0.700, 0.096, 0.038),
        (0.700, 0.151, 0.060),
        (0.700, 0.214, 0.085),
        (0.700, 0.266, 0.106),
        (0.700, 0.309, 0.123),
        (0.700, 0.343, 0.137),
        (0.700, 0.372, 0.148))

      expected.size should be(sampleCount)

      history.zip(expected) foreach {
        case (mockMetrics, expectedData) ⇒
          (mockMetrics, expectedData) match {
            case (Cpu(_, _, loadAverageMock, cpuCombinedMock, cpuStolenMock, _),
              (loadAverageEwma, cpuCombinedEwma, cpuStolenEwma)) ⇒
              loadAverageMock.get should be(loadAverageEwma +- epsilon)
              cpuCombinedMock.get should be(cpuCombinedEwma +- epsilon)
              cpuStolenMock.get should be(cpuStolenEwma +- epsilon)
          }
      }
    }

    "control collector on/off state" in {

      def cycle() = {

        val size1 = metricsHistorySize
        awaitSample()
        val size2 = metricsHistorySize
        size1 should be(size2)

        extension.supervisor ! CollectionStartMessage
        awaitSample()
        val size3 = metricsHistorySize
        size3 should be > (size2)

        extension.supervisor ! CollectionStopMessage
        awaitSample()
        val size4 = metricsHistorySize
        size4 should be >= (size3)

        awaitSample()
        val size5 = metricsHistorySize
        size5 should be(size4)

      }

      (1 to 3) foreach { step ⇒ cycle() }

    }

  }

}

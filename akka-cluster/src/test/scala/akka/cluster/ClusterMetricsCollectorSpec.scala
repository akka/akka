/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import scala.concurrent.Await
import scala.util.{ Try, Failure }

import akka.actor._
import akka.testkit._

object MetricsEnabledSpec {
  val config = """
    akka.cluster.metrics-collector {
      enabled = on
      metrics-interval = 1 s
      gossip-interval = 1 s
      rate-of-decay = 10
    }
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.loglevel = INFO"""
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ClusterMetricsCollectorSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with DefaultTimeout with AbstractMetricsCollectorSpec {
  import system.dispatcher

  "MetricsCollector" must {
    "not raise errors when attempting reflective code in apply" in {
      Try(createMetricsCollector must not be null) match {
        case Failure(e) ⇒ fail("No error should have been raised creating 'createMetricsCollector'.")
        case _          ⇒ //
      }
    }

    "collect accurate metrics for a node in an acceptable duration" in {
      val latch = TestLatch()
      val task = FixedRateTask(system.scheduler, 0 seconds, interval) {
        createMetricsCollector.sample
        latch.countDown()
      }
      Await.ready(latch, timeout.duration)
      task.cancel()
    }

    "collect [" + samples + "] samples of accurate metrics" taggedAs LongRunningTest in {
      val collector = createMetricsCollector
      val latch = TestLatch(samples)
      val task = FixedRateTask(system.scheduler, 0 seconds, interval) {
        testMetrics(collector.sample, collector.isSigar)
        latch.countDown()
      }
      Await.ready(latch, scaleDuration)
      task.cancel()
    }

    def testMetrics(nodeMetrics: NodeMetrics, isSigar: Boolean): Unit = {
      val available = nodeMetrics.metrics.filter(_.isDefined).map(m ⇒ (m.name, m.value.get)).toSeq

      if (isSigar) {
        val expected = available collect {
          case (a, b) if a == "cpuCombined" ⇒ {
            b.doubleValue must be <= (1.0)
            b.doubleValue must be >= (0.0)
            b
          }
          case (a, b) if a == "totalCores"   ⇒ b.intValue must be > (0); b
          case (a, b) if a == "networkMaxRx" ⇒ b.longValue() must be > (BigInt(0).longValue()); b
        }
        expected.size must be(3)
        expected foreach (n ⇒ n must not be (0))
      }
      val systemLoadAverage = available.collectFirst { case (a, b) if a == "systemLoadAverage" ⇒ b.doubleValue must be >= (0.0) }
      val processors = available.collectFirst { case (a, b) if a == "processors" ⇒ b.doubleValue must be >= (0.0) }
      val used = available.collectFirst { case (a, b) if a == "heap-memory-used" ⇒ b }
      val committed = available.collectFirst { case (a, b) if a == "heap-memory-committed" ⇒ b }
      available.collectFirst {
        case (a, b) if a == "heap-memory-max" ⇒ {
          used.get.longValue must be < (b.longValue)
          committed.get.longValue must be < (b.longValue)
          used.get.longValue + committed.get.longValue must be <= (b.longValue)
        }
      }
    }
  }
}

trait AbstractMetricsCollectorSpec {
  this: AkkaSpec ⇒

  val selfAddress = new Address("akka", "localhost")

  val window = 49

  val interval: Duration = 1 seconds

  val scaleDuration = 120 seconds // for long running tests

  val samples = 100

  def createMetricsCollector: MetricsCollector = MetricsCollector(selfAddress, log, system.asInstanceOf[ExtendedActorSystem].dynamicAccess)

}
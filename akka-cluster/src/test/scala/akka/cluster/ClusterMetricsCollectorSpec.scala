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
class ClusterMetricsCollectorSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with AbstractClusterMetricsSpec {
  import system.dispatcher

  val collector = createMetricsCollector

  "MetricsCollector" must {

    "not raise errors when attempting reflective code in apply" in {
      Try(createMetricsCollector must not be null) match {
        case Failure(e) ⇒ fail("No error should have been raised creating 'createMetricsCollector'.")
        case _          ⇒ //
      }
    }

    "collect accurate metrics for a node" in {
      val metrics = collector.sample.metrics.filter(_.isDefined).map(m ⇒ (m.name, m.value.get)).toSeq

      val sigar = metrics collect {
        case (a, b) if a == "cpu-combined" ⇒
          b.doubleValue must be <= (1.0)
          b.doubleValue must be >= (0.0)
          b
        case (a, b) if a == "total-cores"    ⇒ b.intValue must be > (0); b
        case (a, b) if a == "network-max-rx" ⇒ b.longValue must be > (0L); b
        case (a, b) if a == "network-max-tx" ⇒ b.longValue must be > (0L); b
      }
      if (collector.isSigar) sigar.size must be(4)

      val used = metrics collectFirst { case (a, b) if a == "heap-memory-used" ⇒ b }
      val committed = metrics collectFirst { case (a, b) if a == "heap-memory-committed" ⇒ b }
      val mixed = metrics collect {
        case (a, b) if a == "system-load-average"   ⇒ b.doubleValue must be >= (0.0); b // sigar or jmx
        case (a, b) if a == "processors"            ⇒ b.intValue must be >= (0); b
        case (a, b) if a == "heap-memory-used"      ⇒ b.longValue must be >= (0L); b
        case (a, b) if a == "heap-memory-committed" ⇒ b.longValue must be > (0L); b
        case (a, b) if a == "heap-memory-max" ⇒
          used.get.longValue must be < (b.longValue)
          committed.get.longValue must be < (b.longValue)
          used.get.longValue + committed.get.longValue must be <= (b.longValue)
          b
      }
      mixed.size must be >= (4) //max memory may be undefined depending on the OS
    }

    "collect [" + samples + "] node metrics samples in an acceptable duration" taggedAs LongRunningTest in {
      val latch = TestLatch(samples)
      val task = FixedRateTask(system.scheduler, 0 seconds, interval) {
        val sample = collector.sample.metrics
        if (collector.isSigar) sample.size must be >= (8)
        else sample.size must be >= (4)
        latch.countDown()
      }
      Await.ready(latch, longDuration)
      task.cancel()
    }
  }
}

trait AbstractClusterMetricsSpec extends DefaultTimeout {
  this: AkkaSpec ⇒

  val selfAddress = new Address("akka", "localhost")

  val window = 49

  val interval: Duration = 100 millis

  val longDuration = 120 seconds // for long running tests

  val samples = 100

  def createMetricsCollector: MetricsCollector = MetricsCollector(selfAddress, log, system.asInstanceOf[ExtendedActorSystem].dynamicAccess)

}
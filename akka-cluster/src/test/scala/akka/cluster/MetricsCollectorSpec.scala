/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.util.{ Success, Try, Failure }

import akka.actor._
import akka.testkit._
import akka.cluster.NodeMetrics.MetricValues._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

object MetricsEnabledSpec {
  val config = """
    akka.cluster.metrics.enabled = on
    akka.cluster.metrics.collect-interval = 1 s
    akka.cluster.metrics.gossip-interval = 1 s
    akka.cluster.metrics.rate-of-decay = 10
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    """
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsCollectorSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with MetricSpec
  with MetricsCollectorFactory {
  import system.dispatcher

  val collector = createMetricsCollector

  "Metric must" must {

    "merge 2 metrics that are tracking the same metric" in {
      for (i ← 1 to 20) {
        val sample1 = collector.sample.metrics
        val sample2 = collector.sample.metrics
        var merged = sample2 flatMap (latest ⇒ sample1 collect {
          case peer if latest same peer ⇒ {
            val m = peer :+ latest
            assertMerged(latest, peer, m)
            m
          }
        })

        val sample3 = collector.sample.metrics
        val sample4 = collector.sample.metrics
        merged = sample4 flatMap (latest ⇒ sample3 collect {
          case peer if latest same peer ⇒ {
            val m = peer :+ latest
            assertMerged(latest, peer, m)
            m
          }
        })
        merged.size must be(sample3.size)
        merged.size must be(sample4.size)
      }
    }
  }

  "MetricsCollector" must {

    "not raise errors when attempting reflective code in apply" in {
      Try(createMetricsCollector).get must not be null
    }

    "collect accurate metrics for a node" in {
      val sample = collector.sample
      val metrics = sample.metrics.collect { case m if m.isDefined ⇒ (m.name, m.value.get) }
      val used = metrics collectFirst { case (HeapMemoryUsed, b) ⇒ b }
      val committed = metrics collectFirst { case (HeapMemoryCommitted, b) ⇒ b }
      metrics foreach {
        case (TotalCores, b)          ⇒ b.intValue must be > (0)
        case (NetworkInboundRate, b)  ⇒ b.longValue must be > (0L)
        case (NetworkOutboundRate, b) ⇒ b.longValue must be > (0L)
        case (SystemLoadAverage, b)   ⇒ b.doubleValue must be >= (0.0)
        case (Processors, b)          ⇒ b.intValue must be >= (0)
        case (HeapMemoryUsed, b)      ⇒ b.longValue must be >= (0L)
        case (HeapMemoryCommitted, b) ⇒ b.longValue must be > (0L)
        case (HeapMemoryMax, b) ⇒
          b.longValue must be > (0L)
          used.get.longValue must be <= (b.longValue)
          committed.get.longValue must be <= (b.longValue)
        case (CpuCombined, b) ⇒
          b.doubleValue must be <= (1.0)
          b.doubleValue must be >= (0.0)

      }
    }

    "collect SIGAR metrics if it is on the classpath" in {
      collector match {
        case c: SigarMetricsCollector ⇒
          // combined cpu may or may not be defined on a given sampling
          // systemLoadAverage is not present on all platforms
          c.networkMaxRx.isDefined must be(true)
          c.networkMaxTx.isDefined must be(true)
          c.totalCores.isDefined must be(true)
        case _ ⇒
      }
    }

    "collect JMX metrics" in {
      // heap max may be undefined depending on the OS
      // systemLoadAverage is JMX when SIGAR not present, but
      // it's not present on all platforms
      val c = collector.asInstanceOf[JmxMetricsCollector]
      c.heapUsed.isDefined must be(true)
      c.heapCommitted.isDefined must be(true)
      c.processors.isDefined must be(true)
    }

    "collect 50 node metrics samples in an acceptable duration" taggedAs LongRunningTest in within(7 seconds) {
      (1 to 50) foreach { _ ⇒
        val sample = collector.sample
        sample.metrics.size must be >= (3)
        Thread.sleep(100)
      }
    }
  }
}

trait MetricSpec extends WordSpec with MustMatchers { this: { def system: ActorSystem } ⇒

  def assertMasterMetricsAgainstGossipMetrics(master: Set[NodeMetrics], gossip: MetricsGossip): Unit = {
    val masterMetrics = collectNodeMetrics(master)
    val gossipMetrics = collectNodeMetrics(gossip.nodes)
    gossipMetrics.size must be(masterMetrics.size plusOrMinus 1) // combined cpu
  }

  def assertExpectedNodeAddresses(gossip: MetricsGossip, nodes: Set[NodeMetrics]): Unit =
    gossip.nodes.map(_.address) must be(nodes.map(_.address))

  def assertMerged(latest: Metric, peer: Metric, merged: Metric): Unit = if (latest same peer) {
    if (latest.isDefined) {
      if (peer.isDefined) {
        merged.isDefined must be(true)
        merged.value.get must be(latest.value.get)
        merged.average.isDefined must be(latest.average.isDefined)
      } else {
        merged.isDefined must be(true)
        merged.value.get must be(latest.value.get)
        merged.average.isDefined must be(latest.average.isDefined || peer.average.isDefined)
      }
    } else {
      if (peer.isDefined) {
        merged.isDefined must be(true)
        merged.value.get must be(peer.value.get)
        merged.average.isDefined must be(peer.average.isDefined)
      } else {
        merged.isDefined must be(false)
        merged.average.isEmpty must be(true)
      }
    }
  }

  def collectNodeMetrics(nodes: Set[NodeMetrics]): Seq[Metric] = {
    var r: Seq[Metric] = Seq.empty
    nodes.foreach(n ⇒ r ++= n.metrics.filter(_.isDefined))
    r
  }
}

/**
 * Used when testing metrics without full cluster
 */
trait MetricsCollectorFactory { this: AkkaSpec ⇒

  private def extendedActorSystem = system.asInstanceOf[ExtendedActorSystem]

  def selfAddress = extendedActorSystem.provider.rootPath.address

  val defaultRateOfDecay = 10

  def createMetricsCollector: MetricsCollector =
    Try(new SigarMetricsCollector(selfAddress, defaultRateOfDecay, extendedActorSystem.dynamicAccess)) match {
      case Success(sigarCollector) ⇒ sigarCollector
      case Failure(e) ⇒
        log.debug("Metrics will be retreived from MBeans, Sigar failed to load. Reason: " +
          e.getMessage)
        new JmxMetricsCollector(selfAddress, defaultRateOfDecay)
    }

  def isSigar(collector: MetricsCollector): Boolean = collector.isInstanceOf[SigarMetricsCollector]
}

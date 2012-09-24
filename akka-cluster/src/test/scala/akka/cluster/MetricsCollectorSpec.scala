/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import scala.language.postfixOps
import scala.concurrent.util.duration._
import scala.concurrent.util.FiniteDuration
import scala.concurrent.Await
import scala.util.{ Try, Failure }

import akka.actor._
import akka.testkit._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

object MetricsEnabledSpec {
  val config = """
    akka.cluster.metrics.enabled = on
    akka.cluster.metrics.metrics-interval = 1 s
    akka.cluster.metrics.gossip-interval = 1 s
    akka.cluster.metrics.rate-of-decay = 10
    akka.actor.provider = "akka.remote.RemoteActorRefProvider"
    akka.loglevel = INFO"""
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class MetricsCollectorSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with AbstractClusterMetricsSpec with MetricSpec {
  import system.dispatcher

  val collector = createMetricsCollector

  "Metric must" must {
    "create and initialize a new metric or merge an existing one" in {
      for (i ← 0 to samples) {
        val metrics = collector.sample.metrics
        assertCreatedUninitialized(metrics)
        assertInitialized(window, metrics map (_.initialize(window)))
      }
    }

    "merge 2 metrics that are tracking the same metric" in {
      for (i ← 0 to samples) {
        val sample1 = collector.sample.metrics
        val sample2 = collector.sample.metrics
        var merged = sample2 flatMap (latest ⇒ sample1 collect {
          case peer if latest same peer ⇒ {
            val m = peer :+ latest
            assertMerged(latest, peer, m)
            m
          }
        })

        val sample3 = collector.sample.metrics map (_.initialize(window))
        val sample4 = collector.sample.metrics map (_.initialize(window))
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
      Try(createMetricsCollector must not be null) match {
        case Failure(e) ⇒ fail("No error should have been raised creating 'createMetricsCollector'.")
        case _          ⇒ //
      }
    }

    "collect accurate metrics for a node" in {
      val sample = collector.sample
      assertExpectedSampleSize(collector.isSigar, window, sample)
      val metrics = sample.metrics.collect { case m if m.isDefined ⇒ (m.name, m.value.get) }
      val used = metrics collectFirst { case (a, b) if a == "heap-memory-used" ⇒ b }
      val committed = metrics collectFirst { case (a, b) if a == "heap-memory-committed" ⇒ b }
      metrics collect {
        case (a, b) if a == "cpu-combined" ⇒
          b.doubleValue must be <= (1.0)
          b.doubleValue must be >= (0.0)
          b
        case (a, b) if a == "total-cores"           ⇒ b.intValue must be > (0); b
        case (a, b) if a == "network-max-rx"        ⇒ b.longValue must be > (0L); b
        case (a, b) if a == "network-max-tx"        ⇒ b.longValue must be > (0L); b
        case (a, b) if a == "system-load-average"   ⇒ b.doubleValue must be >= (0.0); b
        case (a, b) if a == "processors"            ⇒ b.intValue must be >= (0); b
        case (a, b) if a == "heap-memory-used"      ⇒ b.longValue must be >= (0L); b
        case (a, b) if a == "heap-memory-committed" ⇒ b.longValue must be > (0L); b
        case (a, b) if a == "heap-memory-max" ⇒
          used.get.longValue must be <= (b.longValue)
          committed.get.longValue must be <= (b.longValue)
          b
      }
    }

    "collect SIGAR metrics if it is on the classpath" in {
      if (collector.isSigar) {
        // combined cpu may or may not be defined on a given sampling
        // systemLoadAverage is SIGAR present
        collector.systemLoadAverage.isDefined must be(true)
        collector.networkStats.nonEmpty must be(true)
        collector.networkMaxRx.isDefined must be(true)
        collector.networkMaxTx.isDefined must be(true)
        collector.totalCores.isDefined must be(true)
      }
    }

    "collect JMX metrics" in {
      // heap max may be undefined depending on the OS
      // systemLoadAverage is JMX is SIGAR not present
      collector.systemLoadAverage.isDefined must be(true)
      collector.used.isDefined must be(true)
      collector.committed.isDefined must be(true)
      collector.processors.isDefined must be(true)
    }

    "collect [" + samples + "] node metrics samples in an acceptable duration" taggedAs LongRunningTest in {
      val latch = TestLatch(samples)
      val task = FixedRateTask(system.scheduler, 0 seconds, interval) {
        val sample = collector.sample
        assertCreatedUninitialized(sample.metrics)
        assertExpectedSampleSize(collector.isSigar, window, sample)
        latch.countDown()
      }
      Await.ready(latch, longDuration)
      task.cancel()
    }
  }
}

trait MetricSpec extends WordSpec with MustMatchers {

  def assertMasterMetricsAgainstGossipMetrics(master: Set[NodeMetrics], gossip: MetricsGossip): Unit = {
    val masterMetrics = collectNodeMetrics(master)
    val gossipMetrics = collectNodeMetrics(gossip.nodes)
    gossipMetrics.size must be(masterMetrics.size plusOrMinus 1) // combined cpu
  }

  def assertExpectedNodeAddresses(gossip: MetricsGossip, nodes: Set[NodeMetrics]): Unit =
    gossip.nodes.map(_.address) must be(nodes.map(_.address))

  def assertExpectedSampleSize(isSigar: Boolean, gossip: MetricsGossip): Unit =
    gossip.nodes.foreach(n ⇒ assertExpectedSampleSize(isSigar, gossip.rateOfDecay, n))

  def assertCreatedUninitialized(gossip: MetricsGossip): Unit =
    gossip.nodes.foreach(n ⇒ assertCreatedUninitialized(n.metrics.filterNot(_.trendable)))

  def assertInitialized(gossip: MetricsGossip): Unit =
    gossip.nodes.foreach(n ⇒ assertInitialized(gossip.rateOfDecay, n.metrics))

  def assertCreatedUninitialized(metrics: Set[Metric]): Unit = {
    metrics.size must be > (0)
    metrics foreach { m ⇒
      m.average.isEmpty must be(true)
      if (m.value.isDefined) m.isDefined must be(true)
      if (m.initializable) (m.trendable && m.isDefined && m.average.isEmpty) must be(true)
    }
  }

  def assertInitialized(decay: Int, metrics: Set[Metric]): Unit = if (decay > 0) metrics.filter(_.trendable) foreach { m ⇒
    m.initializable must be(false)
    if (m.isDefined) m.average.isDefined must be(true)
  }

  def assertMerged(latest: Metric, peer: Metric, merged: Metric): Unit = if (latest same peer) {
    if (latest.isDefined) {
      if (peer.isDefined) {
        merged.isDefined must be(true)
        merged.value.get must be(latest.value.get)
        if (latest.trendable) {
          if (latest.initializable) merged.average.isEmpty must be(true)
          else merged.average.isDefined must be(true)
        }
      } else {
        merged.isDefined must be(true)
        merged.value.get must be(latest.value.get)
        if (latest.average.isDefined) merged.average.get must be(latest.average.get)
        else merged.average.isEmpty must be(true)
      }
    } else {
      if (peer.isDefined) {
        merged.isDefined must be(true)
        merged.value.get must be(peer.value.get)
        if (peer.trendable) {
          if (peer.initializable) merged.average.isEmpty must be(true)
          else merged.average.isDefined must be(true)
        }
      } else {
        merged.isDefined must be(false)
        merged.average.isEmpty must be(true)
      }
    }
  }

  def assertExpectedSampleSize(isSigar: Boolean, decay: Int, node: NodeMetrics): Unit = {
    node.metrics.size must be(9)
    val metrics = node.metrics.filter(_.isDefined)
    if (isSigar) { // combined cpu + jmx max heap
      metrics.size must be >= (7)
      metrics.size must be <= (9)
    } else { // jmx max heap
      metrics.size must be >= (4)
      metrics.size must be <= (5)
    }

    if (decay > 0) metrics.collect { case m if m.trendable && (!m.initializable) ⇒ m }.foreach(_.average.isDefined must be(true))
  }

  def collectNodeMetrics(nodes: Set[NodeMetrics]): Seq[Metric] = {
    var r: Seq[Metric] = Seq.empty
    nodes.foreach(n ⇒ r ++= n.metrics.filter(_.isDefined))
    r
  }
}

trait AbstractClusterMetricsSpec extends DefaultTimeout {
  this: AkkaSpec ⇒

  val selfAddress = new Address("akka", "localhost")

  val window = 49

  val interval: FiniteDuration = 100 millis

  val longDuration = 120 seconds // for long running tests

  val samples = 100

  def createMetricsCollector: MetricsCollector = MetricsCollector(selfAddress, log, system.asInstanceOf[ExtendedActorSystem].dynamicAccess)

}
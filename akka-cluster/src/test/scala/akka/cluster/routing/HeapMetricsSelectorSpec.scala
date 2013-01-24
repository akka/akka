/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.routing

import akka.testkit.AkkaSpec
import akka.actor.Address
import akka.actor.RootActorPath
import akka.cluster.NodeMetrics
import akka.cluster.NodeMetrics.MetricValues._
import akka.cluster.Metric
import com.typesafe.config.ConfigFactory

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class HeapMetricsSelectorSpec extends AkkaSpec(ConfigFactory.parseString("""
      akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
      akka.remote.netty.port = 0
      """)) {

  val selector = HeapMetricsSelector

  val a1 = Address("akka", "sys", "a1", 2551)
  val b1 = Address("akka", "sys", "b1", 2551)
  val c1 = Address("akka", "sys", "c1", 2551)
  val d1 = Address("akka", "sys", "d1", 2551)

  val decay = Some(10)

  val nodeMetricsA = NodeMetrics(a1, System.currentTimeMillis, Set(
    Metric(HeapMemoryUsed, Some(BigInt(128)), decay),
    Metric(HeapMemoryCommitted, Some(BigInt(256)), decay),
    Metric(HeapMemoryMax, Some(BigInt(512)), None)))

  val nodeMetricsB = NodeMetrics(b1, System.currentTimeMillis, Set(
    Metric(HeapMemoryUsed, Some(BigInt(256)), decay),
    Metric(HeapMemoryCommitted, Some(BigInt(512)), decay),
    Metric(HeapMemoryMax, Some(BigInt(1024)), None)))

  val nodeMetricsC = NodeMetrics(c1, System.currentTimeMillis, Set(
    Metric(HeapMemoryUsed, Some(BigInt(1024)), decay),
    Metric(HeapMemoryCommitted, Some(BigInt(1024)), decay),
    Metric(HeapMemoryMax, Some(BigInt(1024)), None)))

  val nodeMetrics = Set(nodeMetricsA, nodeMetricsB, nodeMetricsC)

  "MetricsAwareClusterNodeSelector" must {

    "calculate capacity of heap metrics" in {
      val capacity = selector.capacity(nodeMetrics)
      capacity(a1) must be(0.75 plusOrMinus 0.0001)
      capacity(b1) must be(0.75 plusOrMinus 0.0001)
      capacity(c1) must be(0.0 plusOrMinus 0.0001)
    }

    "calculate weights from capacity" in {
      val capacity = Map(a1 -> 0.6, b1 -> 0.3, c1 -> 0.1)
      val weights = selector.weights(capacity)
      weights must be(Map(c1 -> 1, b1 -> 3, a1 -> 6))
    }

    "handle low and zero capacity" in {
      val capacity = Map(a1 -> 0.0, b1 -> 1.0, c1 -> 0.005, d1 -> 0.004)
      val weights = selector.weights(capacity)
      weights must be(Map(a1 -> 0, b1 -> 100, c1 -> 1, d1 -> 0))
    }

    "allocate weighted refs" in {
      val weights = Map(a1 -> 1, b1 -> 3, c1 -> 10)
      val refs = IndexedSeq(
        system.actorFor(RootActorPath(a1) / "user" / "a"),
        system.actorFor(RootActorPath(b1) / "user" / "b"),
        system.actorFor(RootActorPath(c1) / "user" / "c"))
      val result = selector.weightedRefs(refs, a1, weights)
      val grouped = result.groupBy(_.path.address)
      grouped(a1).size must be(1)
      grouped(b1).size must be(3)
      grouped(c1).size must be(10)
    }

    "allocate refs for undefined weight" in {
      val weights = Map(a1 -> 1, b1 -> 2)
      val refs = IndexedSeq(
        system.actorFor(RootActorPath(a1) / "user" / "a"),
        system.actorFor(RootActorPath(b1) / "user" / "b"),
        system.actorFor(RootActorPath(c1) / "user" / "c"))
      val result = selector.weightedRefs(refs, a1, weights)
      val grouped = result.groupBy(_.path.address)
      grouped(a1).size must be(1)
      grouped(b1).size must be(2)
      grouped(c1).size must be(1)
    }

    "allocate weighted local refs" in {
      val weights = Map(a1 -> 2, b1 -> 1, c1 -> 10)
      val refs = IndexedSeq(
        testActor,
        system.actorFor(RootActorPath(b1) / "user" / "b"),
        system.actorFor(RootActorPath(c1) / "user" / "c"))
      val result = selector.weightedRefs(refs, a1, weights)
      result.filter(_ == testActor).size must be(2)
    }

    "not allocate ref with weight zero" in {
      val weights = Map(a1 -> 0, b1 -> 2, c1 -> 10)
      val refs = IndexedSeq(
        system.actorFor(RootActorPath(a1) / "user" / "a"),
        system.actorFor(RootActorPath(b1) / "user" / "b"),
        system.actorFor(RootActorPath(c1) / "user" / "c"))
      val result = selector.weightedRefs(refs, a1, weights)
      result.filter(_ == refs.head).size must be(0)
    }
  }
}
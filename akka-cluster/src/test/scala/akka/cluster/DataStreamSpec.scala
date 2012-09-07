/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ LongRunningTest, TestLatch, ImplicitSender, AkkaSpec }

import language.postfixOps
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration
import System.{ currentTimeMillis ⇒ newTimestamp }
import scala.concurrent.Await
import math.ScalaNumber

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DataStreamSpec extends AkkaSpec(MetricsEnabledSpec.config) with ImplicitSender with AbstractMetricsCollectorSpec with MetricNumericConversions {

  val collector = createMetricsCollector

  import system.dispatcher
  "DataStream" must {
    "calculate the ewma for multiple, variable, data streams" in {
      val latch = TestLatch(samples)
      val initial = collector.sample.metrics.filter(_.isDefined).filter(_.trendable)
      var streams: Set[Metric] = initial.map(m ⇒ m.copy(average = Some(DataStream(window, m.value.get, newTimestamp, newTimestamp))))

      val cancellable = system.scheduler.schedule(0 seconds, 100 millis) {
        val data = collector.sample.metrics.filter(_.isDefined).filter(_.trendable)
        streams = data flatMap (latest ⇒ streams.collect {
          case previous if latest same previous ⇒ {
            val newValue = latest.value.get
            val oldValue = previous.value.get
            val oldDataStream = previous.average.get
            val newDataStream = oldDataStream :+ newValue
            if (newValue != oldValue) {
              newDataStream.ewma must not be (oldValue)
              // sometimes equal: newDataStream.ewma must not be (newValue)
              newDataStream.ewma must not be (oldDataStream.ewma)
            }
            previous.copy(value = latest.value, average = Some(newDataStream))
          }
        })
        latch.countDown()
      }
      Await.ready(latch, scaleDuration)
      cancellable.cancel()
    }

    "calculate the ewma for multiple, variable, data streams as processed by MetricsGossip" taggedAs LongRunningTest in {
      var gossip = MetricsGossip(window)
      val node = NodeMetrics(selfAddress, newTimestamp, collector.sample.metrics)

      gossip = gossip :+ node
      val firstDataSet = gossip.nodes.filter(_.address == node.address).head.metrics.filter(_.trendable)

      val latch = TestLatch(samples)
      val cancellable = system.scheduler.schedule(0 seconds, 100 millis) {
        gossip = gossip :+ node.copy(metrics = collector.sample.metrics, timestamp = newTimestamp)

        val latestSet = gossip.nodes.filter(_.address == node.address).head.metrics.filter(_.trendable)
        latestSet foreach { m ⇒
          val e = m.average.get
          (e.duration > Duration.Zero) must be(true)
          convert(e.ewma) fold (l ⇒ l must not be (0), d ⇒ d must not be (0.0))
          val updated = e :+ m.value.get
          convert(updated.ewma) fold (l ⇒ l must not be (0), d ⇒ d must not be (0.0))
        }
        latch.countDown()
      }
      Await.ready(latch, scaleDuration)
      cancellable.cancel()

      // these will not vary enough to test
      val doesNotVaryQuickly = Seq("heap-memory-max", "heap-memory-committed")

      val testableFirst: Map[String, Metric] = firstDataSet.map(m ⇒ m.name -> m).toMap
      val firstSet = testableFirst.keySet -- doesNotVaryQuickly
      val lastDataSet = gossip.nodes.filter(_.address == node.address).head.metrics.filter(_.trendable)
      val testableLast: Map[String, Metric] = lastDataSet.map(m ⇒ m.name -> m).toMap
      val lastSet = testableLast.keySet -- doesNotVaryQuickly

      firstDataSet collect {
        case old if (firstSet contains old.name) && (lastSet contains old.name) ⇒
          val newMetric = testableLast.get(old.name).get
          val e1 = old.average.get
          val e2 = newMetric.average.get
          (e2.duration > e1.duration) must be(true)
          if (old.value.get != newMetric.value.get) e1.ewma must not be (e2.ewma)
      }
    }
  }
}

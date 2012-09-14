/*
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import akka.testkit.{ LongRunningTest, AkkaSpec }

import language.postfixOps
import scala.concurrent.util.duration._
import scala.concurrent.util.Duration

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DataStreamSpec extends AkkaSpec(MetricsEnabledSpec.config) with AbstractClusterMetricsSpec with MetricNumericConverter {
  import system.dispatcher

  val collector = createMetricsCollector

  "DataStream" must {

    "calculate the ewma for multiple, variable, data streams" taggedAs LongRunningTest in {
      val firstDataSet = collector.sample.metrics filter (_.trendable) map (_.initialize(window))
      var streamingDataSet = firstDataSet

      val cancellable = system.scheduler.schedule(0 seconds, 100 millis) {
        streamingDataSet = collector.sample.metrics.filter(_.trendable).flatMap(latest ⇒ streamingDataSet.collect {
          case streaming if (latest same streaming) && (latest.value.get != streaming.value.get) ⇒ {

            val updatedDataStream = streaming.average.get :+ latest.value.get

            updatedDataStream.timestamp must be > (streaming.average.get.timestamp)
            updatedDataStream.duration.length must be > (streaming.average.get.duration.length)
            updatedDataStream.ewma must not be (streaming.average.get.ewma)
            updatedDataStream.ewma must not be (latest.value.get)

            streaming.copy(value = latest.value, average = Some(updatedDataStream))
          }
        })
      }
      awaitCond(firstDataSet.size == streamingDataSet.size, longDuration)
      cancellable.cancel()

      val finalDataSet = streamingDataSet.map(m ⇒ m.name -> m).toMap
      firstDataSet map {
        first ⇒
          val newMetric = finalDataSet.get(first.name).get
          val e1 = first.average.get
          val e2 = newMetric.average.get

          if (first.value.get != newMetric.value.get) {
            e2.ewma must not be (first.value.get)
            e2.ewma must not be (newMetric.value.get)
          }
          if (first.value.get.longValue > newMetric.value.get.longValue) e1.ewma.longValue must be > e2.ewma.longValue
          else if (first.value.get.longValue < newMetric.value.get.longValue) e1.ewma.longValue must be < e2.ewma.longValue
      }
    }
  }
}

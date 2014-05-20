/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory
import akka.stream.scaladsl.Flow
import akka.testkit.TestProbe
import scala.util.control.NoStackTrace
import scala.collection.immutable

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTimerTransformerSpec extends AkkaSpec {

  import system.dispatcher

  val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))

  "A Flow with TimerTransformer operations" must {
    "produce scheduled ticks as expected" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transform(new TimerTransformer[Int, Int] {
          schedulePeriodically("tick", 100.millis)
          var tickCount = 0
          override def onNext(elem: Int) = List(elem)
          override def onTimer(timerKey: Any) = {
            tickCount += 1
            if (tickCount == 3) cancelTimer("tick")
            List(tickCount)
          }
          override def isComplete: Boolean = !isTimerActive("tick")
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(5)
      consumer.expectNext(1)
      consumer.expectNext(2)
      consumer.expectNext(3)
      consumer.expectComplete()
    }

    "schedule ticks when last transformation step (consume)" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transform(new TimerTransformer[Int, Int] {
          schedulePeriodically("tick", 100.millis)
          var tickCount = 0
          override def onNext(elem: Int) = List(elem)
          override def onTimer(timerKey: Any) = {
            tickCount += 1
            if (tickCount == 3) cancelTimer("tick")
            testActor ! "tick-" + tickCount
            List(tickCount)
          }
          override def isComplete: Boolean = !isTimerActive("tick")
        }).
        consume(materializer)
      val pSub = p.expectSubscription
      expectMsg("tick-1")
      expectMsg("tick-2")
      expectMsg("tick-3")
      pSub.sendComplete()
    }

  }
}

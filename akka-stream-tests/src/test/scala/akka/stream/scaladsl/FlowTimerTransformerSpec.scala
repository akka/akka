/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.ActorMaterializer
import akka.stream.TimerTransformer

import akka.stream.testkit._
import akka.stream.testkit.Utils._

class FlowTimerTransformerSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer()

  "A Flow with TimerTransformer operations" must {
    "produce scheduled ticks as expected" in assertAllStagesStopped {
      val p = TestPublisher.manualProbe[Int]()
      val p2 = Source(p).
        timerTransform(() ⇒ new TimerTransformer[Int, Int] {
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
        runWith(Sink.publisher)
      val subscriber = TestSubscriber.manualProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(5)
      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      subscriber.expectComplete()
    }

    "schedule ticks when last transformation step (consume)" in {
      val p = TestPublisher.manualProbe[Int]()
      val p2 = Source(p).
        timerTransform(() ⇒ new TimerTransformer[Int, Int] {
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
        to(Sink.ignore).run()
      val pSub = p.expectSubscription()
      expectMsg("tick-1")
      expectMsg("tick-2")
      expectMsg("tick-3")
      pSub.sendComplete()
    }

    "propagate error if onTimer throws an exception" in assertAllStagesStopped {
      val exception = new Exception("Expected exception to the rule") with NoStackTrace
      val p = TestPublisher.manualProbe[Int]()
      val p2 = Source(p).
        timerTransform(() ⇒ new TimerTransformer[Int, Int] {
          scheduleOnce("tick", 100.millis)

          def onNext(element: Int) = Nil
          override def onTimer(timerKey: Any) =
            throw exception
        }).runWith(Sink.publisher)

      val subscriber = TestSubscriber.manualProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(5)
      subscriber.expectError(exception)
    }
  }
}

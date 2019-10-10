/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit._
import akka.stream.testkit.scaladsl.StreamTestKit._
import com.github.ghik.silencer.silent

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NoStackTrace

@silent("deprecated") // testing deprecated API
class FlowFromFutureSpec extends StreamSpec {

  "A Flow based on a Future" must {
    "produce one element from already successful Future" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      Source.fromFuture(Future.successful(1)).runWith(Sink.asPublisher(true)).subscribe(c)
      val sub = c.expectSubscription()
      c.expectNoMessage(100.millis)
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce error from already failed Future" in assertAllStagesStopped {
      val ex = new RuntimeException("test") with NoStackTrace
      val c = TestSubscriber.manualProbe[Int]()
      Source.fromFuture(Future.failed[Int](ex)).runWith(Sink.asPublisher(false)).subscribe(c)
      c.expectSubscriptionAndError(ex)
    }

    "produce one element when Future is completed" in assertAllStagesStopped {
      val promise = Promise[Int]()
      val c = TestSubscriber.manualProbe[Int]()
      Source.fromFuture(promise.future).runWith(Sink.asPublisher(true)).subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNoMessage(100.millis)
      promise.success(1)
      c.expectNext(1)
      c.expectComplete()
      c.expectNoMessage(100.millis)
    }

    "produce one element when Future is completed but not before request" in {
      val promise = Promise[Int]()
      val c = TestSubscriber.manualProbe[Int]()
      Source.fromFuture(promise.future).runWith(Sink.asPublisher(true)).subscribe(c)
      val sub = c.expectSubscription()
      promise.success(1)
      c.expectNoMessage(200.millis)
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce elements with multiple subscribers" in assertAllStagesStopped {
      val promise = Promise[Int]()
      val p = Source.fromFuture(promise.future).runWith(Sink.asPublisher(true))
      val c1 = TestSubscriber.manualProbe[Int]()
      val c2 = TestSubscriber.manualProbe[Int]()
      p.subscribe(c1)
      p.subscribe(c2)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      promise.success(1)
      sub2.request(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c1.expectComplete()
      c2.expectComplete()
    }

    "allow cancel before receiving element" in {
      val promise = Promise[Int]()
      val p = Source.fromFuture(promise.future).runWith(Sink.asPublisher(true))
      val keepAlive = TestSubscriber.manualProbe[Int]()
      val c = TestSubscriber.manualProbe[Int]()
      p.subscribe(keepAlive)
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      sub.cancel()
      c.expectNoMessage(500.millis)
      promise.success(1)
      c.expectNoMessage(200.millis)
    }
  }
}

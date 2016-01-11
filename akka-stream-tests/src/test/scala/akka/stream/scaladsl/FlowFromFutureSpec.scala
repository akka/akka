/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import akka.stream.ActorFlowMaterializer
import akka.stream.ActorFlowMaterializerSettings

import akka.stream.testkit.{ AkkaSpec, StreamTestKit }

class FlowFromFutureSpec extends AkkaSpec {

  val settings = ActorFlowMaterializerSettings(system)

  implicit val materializer = ActorFlowMaterializer(settings)

  "A Flow based on a Future" must {
    "produce one element from already successful Future" in {
      val p = Source(Future.successful(1)).runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      c.expectNoMsg(100.millis)
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce error from already failed Future" in {
      val ex = new RuntimeException("test") with NoStackTrace
      val p = Source(Future.failed[Int](ex)).runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectError(ex)
    }

    "produce one element when Future is completed" in {
      val promise = Promise[Int]()
      val p = Source(promise.future).runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNoMsg(100.millis)
      promise.success(1)
      c.expectNext(1)
      c.expectComplete()
      c.expectNoMsg(100.millis)
    }

    "produce one element when Future is completed but not before request" in {
      val promise = Promise[Int]()
      val p = Source(promise.future).runWith(Sink.publisher())
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      promise.success(1)
      c.expectNoMsg(200.millis)
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val promise = Promise[Int]()
      val p = Source(promise.future).runWith(Sink.publisher())
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
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

    "produce elements to later subscriber" in {
      val promise = Promise[Int]()
      val p = Source(promise.future).runWith(Sink.publisher())
      val keepAlive = StreamTestKit.SubscriberProbe[Int]()
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(keepAlive)
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      promise.success(1)
      c1.expectNext(1)
      c1.expectComplete()
      p.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub2.request(1)
      c2.expectNext(1)
      c2.expectComplete()
    }

    "allow cancel before receiving element" in {
      val promise = Promise[Int]()
      val p = Source(promise.future).runWith(Sink.publisher())
      val keepAlive = StreamTestKit.SubscriberProbe[Int]()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(keepAlive)
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      sub.cancel()
      c.expectNoMsg(500.millis)
      promise.success(1)
      c.expectNoMsg(200.millis)
    }
  }
}
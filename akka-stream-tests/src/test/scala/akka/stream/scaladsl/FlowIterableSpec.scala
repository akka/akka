/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.duration._

import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings

import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.StreamTestKit.{ OnComplete, OnError, OnNext }

class FlowIterableSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 512)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow based on an iterable" must {
    "produce elements" in {
      val p = Source(1 to 3).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectNoMsg(100.millis)
      sub.request(2)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "complete empty" in {
      val p = Source(List.empty[Int]).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectSubscription()
      c.expectComplete()
      c.expectNoMsg(100.millis)

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectSubscription()
      c2.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val p = Source(1 to 3).runWith(Sink.publisher)
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c1)
      p.subscribe(c2)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c2.expectNext(2)
      c1.expectNoMsg(100.millis)
      c2.expectNoMsg(100.millis)
      sub1.request(2)
      sub2.request(2)
      c1.expectNext(2)
      c1.expectNext(3)
      c2.expectNext(3)
      c1.expectComplete()
      c2.expectComplete()
    }

    "produce elements to later subscriber" in {
      val p = Source(1 to 3).runWith(Sink.publisher)
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      p.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub2.request(2)
      // starting from first element, new iterator per subscriber
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNoMsg(100.millis)
      sub2.request(1)
      c2.expectNext(3)
      c2.expectComplete()
      sub1.request(2)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "produce elements with one transformation step" in {
      val p = Source(1 to 3).map(_ * 2).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(2)
      c.expectNext(4)
      c.expectNext(6)
      c.expectComplete()
    }

    "produce elements with two transformation steps" in {
      val p = Source(List(1, 2, 3, 4)).filter(_ % 2 == 0).map(_ * 2).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(4)
      c.expectNext(8)
      c.expectComplete()
    }
  }
}

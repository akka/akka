/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit.OnNext
import akka.stream.testkit.StreamTestKit.OnComplete
import akka.stream.testkit.StreamTestKit.OnError
import akka.stream.MaterializerSettings

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowIteratorSpec extends AkkaSpec {

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 2)
    .withFanOutBuffer(initialSize = 4, maxSize = 4)

  implicit val materializer = FlowMaterializer(settings)

  "A Flow based on an iterator" must {
    "produce elements" in {
      val p = FlowFrom(List(1, 2, 3).iterator).toPublisher()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectNoMsg(100.millis)
      sub.request(3)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "complete empty" in {
      val p = FlowFrom(List.empty[Int].iterator).toPublisher()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectComplete()
      c.expectNoMsg(100.millis)

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val p = FlowFrom(List(1, 2, 3).iterator).toPublisher()
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
      val p = FlowFrom(List(1, 2, 3).iterator).toPublisher()
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      p.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      // element 1 is already gone
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
      sub1.request(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "produce elements with one transformation step" in {
      val p = FlowFrom(List(1, 2, 3).iterator).map(_ * 2).toPublisher()
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
      val p = FlowFrom(List(1, 2, 3, 4).iterator).filter(_ % 2 == 0).map(_ * 2).toPublisher()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(4)
      c.expectNext(8)
      c.expectComplete()
    }

    "allow cancel before receiving all elements" in {
      val count = 100000
      val p = FlowFrom((1 to count).iterator).toPublisher()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(count)
      c.expectNext(1)
      sub.cancel()
      val got = c.probe.receiveWhile(3.seconds) {
        case _: OnNext[_] ⇒
        case OnComplete   ⇒ fail("Cancel expected before OnComplete")
        case OnError(e)   ⇒ fail(e)
      }
      got.size should be < (count - 1)
    }

  }
}
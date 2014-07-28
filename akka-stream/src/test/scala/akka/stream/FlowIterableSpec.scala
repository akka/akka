/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.scaladsl.Flow
import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.stream.testkit.StreamTestKit.{ OnComplete, OnError, OnNext }

import scala.concurrent.duration._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowIterableSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512,
    dispatcher = "akka.test.stream-dispatcher"))

  "A Flow based on an iterable" must {
    "produce elements" in {
      val p = Flow(List(1, 2, 3)).toPublisher(materializer)
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
      val p = Flow(List.empty[Int]).toPublisher(materializer)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectComplete()
      c.expectNoMsg(100.millis)

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val p = Flow(List(1, 2, 3)).toPublisher(materializer)
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
      val p = Flow(List(1, 2, 3)).toPublisher(materializer)
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
      val p = Flow(List(1, 2, 3)).map(_ * 2).toPublisher(materializer)
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
      val p = Flow(List(1, 2, 3, 4)).filter(_ % 2 == 0).map(_ * 2).toPublisher(materializer)
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
      val p = Flow(1 to count).toPublisher(materializer)
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

    "have value equality of publisher" in {
      val p1 = Flow(List(1, 2, 3)).toPublisher(materializer)
      val p2 = Flow(List(1, 2, 3)).toPublisher(materializer)
      p1 should be(p2)
      p2 should be(p1)
      val p3 = Flow(List(1, 2, 3, 4)).toPublisher(materializer)
      p1 should not be (p3)
      p3 should not be (p1)
      val p4 = Flow(Vector.empty[String]).toPublisher(materializer)
      val p5 = Flow(Set.empty[String]).toPublisher(materializer)
      p1 should not be (p4)
      p4 should be(p5)
      p5 should be(p4)
      val p6 = Flow(List(1, 2, 3).iterator).toPublisher(materializer)
      p1 should not be (p6)
      p6 should not be (p1)
    }
  }
}
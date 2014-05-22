/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import scala.collection.immutable
import scala.concurrent.duration._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import org.reactivestreams.api.Consumer
import org.reactivestreams.spi.Subscriber
import akka.testkit.TestProbe
import org.reactivestreams.spi.Subscription

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SynchronousProducerFromIterableSpec extends AkkaSpec {

  "A SynchronousProducerFromIterable" must {
    "produce elements" in {
      val p = SynchronousProducerFromIterable(List(1, 2, 3))
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(1)
      c.expectNext(1)
      c.expectNoMsg(100.millis)
      sub.requestMore(2)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "complete empty" in {
      val p = SynchronousProducerFromIterable(List.empty[Int])
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      c.expectComplete()
      c.expectNoMsg(100.millis)

      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(c2)
      c2.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val p = SynchronousProducerFromIterable(List(1, 2, 3))
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(c1)
      p.produceTo(c2)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.requestMore(1)
      sub2.requestMore(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c2.expectNext(2)
      c1.expectNoMsg(100.millis)
      c2.expectNoMsg(100.millis)
      sub1.requestMore(2)
      sub2.requestMore(2)
      c1.expectNext(2)
      c1.expectNext(3)
      c2.expectNext(3)
      c1.expectComplete()
      c2.expectComplete()
    }

    "produce elements to later subscriber" in {
      val p = SynchronousProducerFromIterable(List(1, 2, 3))
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(c1)

      val sub1 = c1.expectSubscription()
      sub1.requestMore(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      p.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub2.requestMore(2)
      // starting from first element, new iterator per subscriber
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNoMsg(100.millis)
      sub2.requestMore(1)
      c2.expectNext(3)
      c2.expectComplete()
      sub1.requestMore(2)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "not produce after cancel" in {
      val p = SynchronousProducerFromIterable(List(1, 2, 3))
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(1)
      c.expectNext(1)
      sub.cancel()
      sub.requestMore(2)
      c.expectNoMsg(100.millis)
    }

    "not produce after cancel from onNext" in {
      val p = SynchronousProducerFromIterable(List(1, 2, 3, 4, 5))
      val probe = TestProbe()
      p.produceTo(new Consumer[Int] with Subscriber[Int] {
        var sub: Subscription = _
        override val getSubscriber: Subscriber[Int] = this
        override def onError(cause: Throwable): Unit = probe.ref ! cause
        override def onComplete(): Unit = probe.ref ! "complete"
        override def onNext(element: Int): Unit = {
          probe.ref ! element
          if (element == 3) sub.cancel()
        }
        override def onSubscribe(subscription: Subscription): Unit = {
          sub = subscription
          sub.requestMore(10)
        }
      })

      probe.expectMsg(1)
      probe.expectMsg(2)
      probe.expectMsg(3)
      probe.expectNoMsg(500.millis)
    }

    "produce onError when iterator throws" in {
      val iterable = new immutable.Iterable[Int] {
        override def iterator: Iterator[Int] = new Iterator[Int] {
          private var n = 0
          override def hasNext: Boolean = n < 3
          override def next(): Int = {
            n += 1
            if (n == 2) throw new IllegalStateException("not two")
            n
          }
        }
      }
      val p = SynchronousProducerFromIterable(iterable)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(1)
      c.expectNext(1)
      c.expectNoMsg(100.millis)
      sub.requestMore(2)
      c.expectError.getMessage should be("not two")
      sub.requestMore(2)
      c.expectNoMsg(100.millis)
    }

    "handle reentrant requests" in {
      val N = 50000
      val p = SynchronousProducerFromIterable(1 to N)
      val probe = TestProbe()
      p.produceTo(new Consumer[Int] with Subscriber[Int] {
        var sub: Subscription = _
        override val getSubscriber: Subscriber[Int] = this
        override def onError(cause: Throwable): Unit = probe.ref ! cause
        override def onComplete(): Unit = probe.ref ! "complete"
        override def onNext(element: Int): Unit = {
          probe.ref ! element
          sub.requestMore(1)

        }
        override def onSubscribe(subscription: Subscription): Unit = {
          sub = subscription
          sub.requestMore(1)
        }
      })
      probe.receiveN(N) should be((1 to N).toVector)
      probe.expectMsg("complete")
    }

    "have value equality of producer" in {
      val p1 = SynchronousProducerFromIterable(List(1, 2, 3))
      val p2 = SynchronousProducerFromIterable(List(1, 2, 3))
      p1 should be(p2)
      p2 should be(p1)
      val p3 = SynchronousProducerFromIterable(List(1, 2, 3, 4))
      p1 should not be (p3)
      p3 should not be (p1)
      val p4 = SynchronousProducerFromIterable(Vector.empty[String])
      val p5 = SynchronousProducerFromIterable(Set.empty[String])
      p1 should not be (p4)
      p4 should be(p5)
      p5 should be(p4)
    }

    "have nice toString" in {
      SynchronousProducerFromIterable(List(1, 2, 3)).toString should be("SynchronousProducerFromIterable(1, 2, 3)")
    }
  }
}

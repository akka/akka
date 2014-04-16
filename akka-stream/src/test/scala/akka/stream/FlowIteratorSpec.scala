/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.OnNext
import akka.stream.testkit.OnComplete
import akka.stream.testkit.OnError
import akka.stream.scaladsl.Flow

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowIteratorSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 4,
    maxFanOutBufferSize = 4))

  "A Flow based on an iterator" must {
    "produce elements" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(1)
      c.expectNext(1)
      c.expectNoMsg(100.millis)
      sub.requestMore(3)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "complete empty" in {
      val p = Flow(List.empty[Int].iterator).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      c.expectComplete()
      c.expectNoMsg(100.millis)

      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(c2)
      c2.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
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
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(c1)

      val sub1 = c1.expectSubscription()
      sub1.requestMore(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      p.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub2.requestMore(3)
      // element 1 is already gone
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
      sub1.requestMore(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "produce elements with one transformation step" in {
      val p = Flow(List(1, 2, 3).iterator).map(_ * 2).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext(2)
      c.expectNext(4)
      c.expectNext(6)
      c.expectComplete()
    }

    "produce elements with two transformation steps" in {
      val p = Flow(List(1, 2, 3, 4).iterator).filter(_ % 2 == 0).map(_ * 2).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext(4)
      c.expectNext(8)
      c.expectComplete()
    }

    "allow cancel before receiving all elements" in {
      val count = 100000
      val p = Flow((1 to count).iterator).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(count)
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
/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.duration._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.OnComplete
import akka.stream.testkit.StreamTestKit.OnError
import akka.stream.testkit.StreamTestKit.OnNext

class FlowThunkSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "A Flow based on a thunk generator" must {
    "produce elements" in {

      val iter = List(1, 2, 3).iterator
      val p = FlowFrom(() ⇒ if (iter.hasNext) Some(iter.next()) else None).map(_ + 10).toPublisher()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(11)
      c.expectNoMsg(100.millis)
      sub.request(3)
      c.expectNext(12)
      c.expectNext(13)
      c.expectComplete()
    }

    "complete empty" in {
      val p = FlowFrom(() ⇒ None).toPublisher()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectComplete()
      c.expectNoMsg(100.millis)
    }

    "allow cancel before receiving all elements" in {
      val count = 100000
      val iter = (1 to count).iterator
      val p = FlowFrom(() ⇒ if (iter.hasNext) Some(iter.next()) else None).toPublisher()
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
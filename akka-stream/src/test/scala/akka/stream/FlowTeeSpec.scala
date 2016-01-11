/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowBroadcastSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16,
    dispatcher = "akka.test.stream-dispatcher"))

  "A broadcast" must {

    "broadcast to other subscriber" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      val p = Flow(List(1, 2, 3)).
        broadcast(c2).
        toPublisher()
      p.subscribe(c1)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNoMsg(100.millis)
      sub1.request(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
      sub2.request(3)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "produce to other even though downstream cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      val p = Flow(List(1, 2, 3)).
        broadcast(c2).
        toPublisher()
      p.subscribe(c1)
      val sub1 = c1.expectSubscription()
      sub1.cancel()
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "produce to downstream even though other cancels" in {
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      val p = Flow(List(1, 2, 3)).
        broadcast(c1).
        toPublisher()
      p.subscribe(c2)
      val sub1 = c1.expectSubscription()
      sub1.cancel()
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

  }

}
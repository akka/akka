/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTeeSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 16,
    initialFanOutBufferSize = 1,
    maxFanOutBufferSize = 16))

  "A Tee" must {

    "tee to other consumer" in {
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      val p = Flow(List(1, 2, 3)).
        tee(c2).
        toProducer(materializer)
      p.produceTo(c1)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.requestMore(1)
      sub2.requestMore(2)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNoMsg(100.millis)
      sub1.requestMore(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
      sub2.requestMore(3)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "not produce before the other consumer has requested elements" in {
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      val p = Flow(List(1, 2, 3)).
        tee(c2).
        toProducer(materializer)
      p.produceTo(c1)
      val sub1 = c1.expectSubscription()
      sub1.requestMore(1)
      val sub2 = c2.expectSubscription()
      // nothing until sub2.requestMore
      c1.expectNoMsg(100.millis)
      sub2.requestMore(1)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      c2.expectNext(1)
      c2.expectNoMsg(100.millis)
      sub1.requestMore(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
      sub2.requestMore(3)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "not produce before downstream has requested elements" in {
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      val p = Flow(List(1, 2, 3)).
        tee(c1).
        toProducer(materializer)
      p.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub2.requestMore(1)
      val sub1 = c1.expectSubscription()
      // nothing until sub2.requestMore
      c2.expectNoMsg(100.millis)
      sub1.requestMore(1)
      c2.expectNext(1)
      c2.expectNoMsg(100.millis)
      c1.expectNext(1)
      c1.expectNoMsg(100.millis)
      sub2.requestMore(3)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
      sub1.requestMore(3)
      c1.expectNext(2)
      c1.expectNext(3)
      c1.expectComplete()
    }

    "produce to other even though downstream cancels" in {
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      val p = Flow(List(1, 2, 3)).
        tee(c2).
        toProducer(materializer)
      p.produceTo(c1)
      val sub1 = c1.expectSubscription()
      sub1.cancel()
      val sub2 = c2.expectSubscription()
      sub2.requestMore(3)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

    "produce to downstream even though other cancels" in {
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      val p = Flow(List(1, 2, 3)).
        tee(c1).
        toProducer(materializer)
      p.produceTo(c2)
      val sub1 = c1.expectSubscription()
      sub1.cancel()
      val sub2 = c2.expectSubscription()
      sub2.requestMore(3)
      c2.expectNext(1)
      c2.expectNext(2)
      c2.expectNext(3)
      c2.expectComplete()
    }

  }

}
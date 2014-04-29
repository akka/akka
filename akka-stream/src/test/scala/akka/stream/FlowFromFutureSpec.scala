/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.OnNext
import akka.dispatch.OnComplete
import akka.stream.testkit.OnComplete
import akka.stream.testkit.OnError
import akka.stream.testkit.OnSubscribe
import akka.stream.scaladsl.Flow
import scala.concurrent.Future
import scala.concurrent.Promise

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowFromFutureSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings())

  "A Flow based on a Future" must {
    "produce one element from already successful Future" in {
      val p = Flow(Future.successful(1)).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      c.expectNoMsg(100.millis)
      sub.requestMore(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce error from already failed Future" in {
      val ex = new RuntimeException("test")
      val p = Flow(Future.failed[Int](ex)).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      c.expectError(ex)
    }

    "produce one element when Future is completed" in {
      val promise = Promise[Int]()
      val p = Flow(promise.future).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(1)
      c.expectNoMsg(100.millis)
      promise.success(1)
      c.expectNext(1)
      c.expectComplete()
      c.expectNoMsg(100.millis)
    }

    "produce one element when Future is completed but not before request" in {
      val promise = Promise[Int]()
      val p = Flow(promise.future).toProducer(materializer)
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(c)
      val sub = c.expectSubscription()
      promise.success(1)
      c.expectNoMsg(200.millis)
      sub.requestMore(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce elements with multiple subscribers" in {
      val promise = Promise[Int]()
      val p = Flow(promise.future).toProducer(materializer)
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(c1)
      p.produceTo(c2)
      val sub1 = c1.expectSubscription()
      val sub2 = c2.expectSubscription()
      sub1.requestMore(1)
      promise.success(1)
      sub2.requestMore(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c1.expectComplete()
      c2.expectComplete()
    }

    "produce elements to later subscriber" in {
      val promise = Promise[Int]()
      val p = Flow(promise.future).toProducer(materializer)
      val keepAlive = StreamTestKit.consumerProbe[Int]
      val c1 = StreamTestKit.consumerProbe[Int]
      val c2 = StreamTestKit.consumerProbe[Int]
      p.produceTo(keepAlive)
      p.produceTo(c1)

      val sub1 = c1.expectSubscription()
      sub1.requestMore(1)
      promise.success(1)
      c1.expectNext(1)
      c1.expectComplete()
      p.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub2.requestMore(1)
      c2.expectNext(1)
      c2.expectComplete()
    }

    "allow cancel before receiving element" in {
      val promise = Promise[Int]()
      val p = Flow(promise.future).toProducer(materializer)
      val keepAlive = StreamTestKit.consumerProbe[Int]
      val c = StreamTestKit.consumerProbe[Int]
      p.produceTo(keepAlive)
      p.produceTo(c)
      val sub = c.expectSubscription()
      sub.requestMore(1)
      sub.cancel()
      c.expectNoMsg(500.millis)
      promise.success(1)
      c.expectNoMsg(200.millis)
    }
  }
}
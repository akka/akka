/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NoStackTrace
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.testkit.TestProbe
import akka.testkit.TestLatch
import scala.concurrent.Await

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowMapFutureSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    dispatcher = "akka.test.stream-dispatcher"))

  "A Flow with mapFuture" must {

    "produce future elements" in {
      val c = StreamTestKit.consumerProbe[Int]
      implicit val ec = system.dispatcher
      val p = Flow(1 to 3).mapFuture(n ⇒ Future(n)).produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.requestMore(2)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMsg(200.millis)
      sub.requestMore(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "produce future elements in order" in {
      val c = StreamTestKit.consumerProbe[Int]
      implicit val ec = system.dispatcher
      val p = Flow(1 to 50).mapFuture(n ⇒ Future {
        Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10))
        n
      }).produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.requestMore(1000)
      for (n ← 1 to 50) c.expectNext(n)
      c.expectComplete()
    }

    "not run more futures than requested elements" in {
      val probe = TestProbe()
      val c = StreamTestKit.consumerProbe[Int]
      implicit val ec = system.dispatcher
      val p = Flow(1 to 20).mapFuture(n ⇒ Future {
        probe.ref ! n
        n
      }).produceTo(materializer, c)
      val sub = c.expectSubscription()
      // nothing before requested
      probe.expectNoMsg(500.millis)
      sub.requestMore(1)
      probe.expectMsg(1)
      probe.expectNoMsg(500.millis)
      sub.requestMore(2)
      probe.receiveN(2).toSet should be(Set(2, 3))
      probe.expectNoMsg(500.millis)
      sub.requestMore(10)
      probe.receiveN(10).toSet should be((4 to 13).toSet)
      probe.expectNoMsg(200.millis)

      for (n ← 1 to 13) c.expectNext(n)
      c.expectNoMsg(200.millis)
    }

    "signal future failure" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.consumerProbe[Int]
      implicit val ec = system.dispatcher
      val p = Flow(1 to 5).mapFuture(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err1") with NoStackTrace
        else {
          Await.ready(latch, 10.seconds)
          n
        }
      }).produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectError.getMessage should be("err1")
      latch.countDown()
    }

    "signal error from mapFuture" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.consumerProbe[Int]
      implicit val ec = system.dispatcher
      val p = Flow(1 to 5).mapFuture(n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        produceTo(materializer, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectError.getMessage should be("err2")
      latch.countDown()
    }

  }
}
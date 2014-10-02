/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.concurrent.duration._
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.util.control.NoStackTrace
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.testkit.TestProbe
import akka.testkit.TestLatch
import scala.concurrent.Await
import scala.concurrent.Future

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowMapAsyncSpec extends AkkaSpec {

  implicit val materializer = FlowMaterializer()

  "A Flow with mapAsync" must {

    "produce future elements" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 3).mapAsync(n ⇒ Future(n)).publishTo(c)
      val sub = c.expectSubscription()
      sub.request(2)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNoMsg(200.millis)
      sub.request(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "produce future elements in order" in {
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 50).mapAsync(n ⇒ Future {
        Thread.sleep(ThreadLocalRandom.current().nextInt(1, 10))
        n
      }).publishTo(c)
      val sub = c.expectSubscription()
      sub.request(1000)
      for (n ← 1 to 50) c.expectNext(n)
      c.expectComplete()
    }

    "not run more futures than requested elements" in {
      val probe = TestProbe()
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 20).mapAsync(n ⇒ Future {
        probe.ref ! n
        n
      }).publishTo(c)
      val sub = c.expectSubscription()
      // nothing before requested
      probe.expectNoMsg(500.millis)
      sub.request(1)
      probe.expectMsg(1)
      probe.expectNoMsg(500.millis)
      sub.request(2)
      probe.receiveN(2).toSet should be(Set(2, 3))
      probe.expectNoMsg(500.millis)
      sub.request(10)
      probe.receiveN(10).toSet should be((4 to 13).toSet)
      probe.expectNoMsg(200.millis)

      for (n ← 1 to 13) c.expectNext(n)
      c.expectNoMsg(200.millis)
    }

    "signal future failure" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(n ⇒ Future {
        if (n == 3) throw new RuntimeException("err1") with NoStackTrace
        else {
          Await.ready(latch, 10.seconds)
          n
        }
      }).publishTo(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err1")
      latch.countDown()
    }

    "signal error from mapAsync" in {
      val latch = TestLatch(1)
      val c = StreamTestKit.SubscriberProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsync(n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        publishTo(c)
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err2")
      latch.countDown()
    }

  }
}

/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.ActorOperationAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.testkit.TestLatch

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlowMapAsyncUnorderedSpec extends AsyncUnorderedSetup {

  override def functionToTest[T, Out, Mat](flow: Source[Out, Mat], parallelism: Int, f: Out ⇒ T): Source[T, Mat] =
    flow.mapAsyncUnordered(parallelism)(out ⇒ Future(f(out))(system.dispatcher))

  "A Flow with mapAsyncUnordered" must {

    commonTests()

    "signal error from mapAsyncUnordered" in assertAllStagesStopped {
      val latch = TestLatch(1)
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val p = Source(1 to 5).mapAsyncUnordered(4)(n ⇒
        if (n == 3) throw new RuntimeException("err2") with NoStackTrace
        else {
          Future {
            Await.ready(latch, 10.seconds)
            n
          }
        }).
        to(Sink(c)).run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectError.getMessage should be("err2")
      latch.countDown()
    }

    "resume after multiple failures" in assertAllStagesStopped {
      val futures: List[Future[String]] = List(
        Future.failed(Utils.TE("failure1")),
        Future.failed(Utils.TE("failure2")),
        Future.failed(Utils.TE("failure3")),
        Future.failed(Utils.TE("failure4")),
        Future.failed(Utils.TE("failure5")),
        Future.successful("happy!"))

      Await.result(
        Source(futures)
          .mapAsyncUnordered(2)(identity).withAttributes(supervisionStrategy(resumingDecider))
          .runWith(Sink.head), 3.seconds) should ===("happy!")
    }

    "resume when mapAsyncUnordered throws" in {
      implicit val ec = system.dispatcher
      Source(1 to 5)
        .mapAsyncUnordered(4)(n ⇒
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n))
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])
        .request(10)
        .expectNextUnordered(1, 2, 4, 5)
        .expectComplete()
    }

  }
}

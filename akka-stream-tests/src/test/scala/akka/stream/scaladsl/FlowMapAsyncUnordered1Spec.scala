/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.Supervision.resumingDecider
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.testkit.TestLatch
import akka.testkit.TestProbe

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlowMapAsyncUnordered1Spec extends StreamSpec {

  "A Flow with mapAsyncUnordered" must {

    "produce future elements in the order they are ready" in {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      val latch = (1 to 4).map(_ -> TestLatch(1)).toMap
      Source(1 to 4)
        .mapAsyncUnordered(1)(n =>
          Future {
            Await.ready(latch(n), 5.seconds)
            n
          })
        .to(Sink.fromSubscriber(c))
        .run()
      val sub = c.expectSubscription()
      sub.request(5)
      latch(1).countDown()
      c.expectNext(6.seconds, 1)
      latch(2).countDown()
      c.expectNext(6.seconds, 2)
      latch(3).countDown()
      c.expectNext(6.seconds, 3)
      latch(4).countDown()
      c.expectNext(6.seconds, 4)
      c.expectComplete()
    }

    "complete without requiring further demand (parallelism = 1)" in {
      import system.dispatcher
      Source
        .single(1)
        .mapAsyncUnordered(1)(v => Future { Thread.sleep(20); v })
        .runWith(TestSink[Int]())
        .requestNext(1)
        .expectComplete()
    }

    "complete without requiring further demand with already completed future (parallelism = 1)" in {
      Source
        .single(1)
        .mapAsyncUnordered(1)(v => Future.successful(v))
        .runWith(TestSink[Int]())
        .requestNext(1)
        .expectComplete()
    }

    "complete without requiring further demand (parallelism = 1) with multi values" in {
      import system.dispatcher
      val probe =
        Source(1 :: 2 :: Nil).mapAsyncUnordered(1)(v => Future { Thread.sleep(20); v }).runWith(TestSink[Int]())

      probe.request(2).expectNextN(2)
      probe.expectComplete()
    }

    "complete without requiring further demand with already completed future (parallelism = 1) with multi values" in {
      val probe = Source(1 :: 2 :: Nil).mapAsyncUnordered(1)(v => Future.successful(v)).runWith(TestSink[Int]())

      probe.request(2).expectNextN(2)
      probe.expectComplete()
    }

    "not run more futures than requested elements" in {
      val probe = TestProbe()
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      Source(1 to 20)
        .mapAsyncUnordered(1)(n =>
          if (n % 3 == 0) {
            probe.ref ! n
            Future.successful(n)
          } else
            Future {
              probe.ref ! n
              n
            })
        .to(Sink.fromSubscriber(c))
        .run()
      val sub = c.expectSubscription()
      c.expectNoMessage(200.millis)
      probe.expectNoMessage(Duration.Zero)
      sub.request(1)
      var got = Set(c.expectNext())
      probe.expectMsg(1)
      probe.expectNoMessage(500.millis)
      sub.request(19)
      probe.expectMsgAllOf(2 to 20: _*)
      c.within(3.seconds) {
        for (_ <- 2 to 20) got += c.expectNext()
      }

      got should be((1 to 20).toSet)
      c.expectComplete()
    }

    "signal future failure" in {
      val c = TestSubscriber.manualProbe[Int]()
      implicit val ec = system.dispatcher
      Source(1 to 5)
        .mapAsyncUnordered(1)(n =>
          Future {
            if (n == 3) throw new RuntimeException("err1") with NoStackTrace
            else {
              n
            }
          })
        .to(Sink.fromSubscriber(c))
        .run()
      val sub = c.expectSubscription()
      sub.request(10)
      c.expectNext(1)
      c.expectNext(2)
      c.expectError().getMessage should be("err1")
    }

    "signal future failure asap" in {
      val latch = TestLatch(1)
      val done = Source(1 to 5)
        .map { n =>
          if (n == 1) n
          else {
            // slow upstream should not block the error
            Await.ready(latch, 10.seconds)
            n
          }
        }
        .mapAsyncUnordered(1) { n =>
          if (n == 1) Future.failed(new RuntimeException("err1") with NoStackTrace)
          else Future.successful(n)
        }
        .runWith(Sink.ignore)
      intercept[RuntimeException] {
        Await.result(done, remainingOrDefault)
      }.getMessage should be("err1")
      latch.countDown()
    }

    "resume after future failure" in {
      implicit val ec = system.dispatcher
      Source(1 to 5)
        .mapAsyncUnordered(1)(n =>
          Future {
            if (n == 3) throw new RuntimeException("err3") with NoStackTrace
            else n
          })
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink[Int]())
        .request(10)
        .expectNextUnordered(1, 2, 4, 5)
        .expectComplete()
    }

    "resume after multiple failures" in {
      val futures: List[Future[String]] = List(
        Future.failed(Utils.TE("failure1")),
        Future.failed(Utils.TE("failure2")),
        Future.failed(Utils.TE("failure3")),
        Future.failed(Utils.TE("failure4")),
        Future.failed(Utils.TE("failure5")),
        Future.successful("happy!"))

      Await.result(
        Source(futures)
          .mapAsyncUnordered(1)(identity)
          .withAttributes(supervisionStrategy(resumingDecider))
          .runWith(Sink.head),
        3.seconds) should ===("happy!")
    }

    "finish after future failure" in {
      import system.dispatcher
      Await.result(
        Source(1 to 3)
          .mapAsyncUnordered(1)(n =>
            Future {
              if (n == 3) throw new RuntimeException("err3b") with NoStackTrace
              else n
            })
          .withAttributes(supervisionStrategy(resumingDecider))
          .grouped(10)
          .runWith(Sink.head),
        1.second) should be(Seq(1, 2))
    }

    "resume when mapAsyncUnordered throws" in {
      implicit val ec = system.dispatcher
      Source(1 to 5)
        .mapAsyncUnordered(1)(n =>
          if (n == 3) throw new RuntimeException("err4") with NoStackTrace
          else Future(n))
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink[Int]())
        .request(10)
        .expectNextUnordered(1, 2, 4, 5)
        .expectComplete()
    }

    "ignore element when future is completed with null" in {
      val flow = Flow[Int].mapAsyncUnordered[String](1) {
        case 2 => Future.successful(null)
        case x => Future.successful(x.toString)
      }
      val result = Source(List(1, 2, 3)).via(flow).runWith(Sink.seq)

      result.futureValue should contain.only("1", "3")
    }

    "continue emitting after a sequence of nulls" in {
      val flow = Flow[Int].mapAsyncUnordered[String](1) { value =>
        if (value == 0 || value >= 100) Future.successful(value.toString)
        else Future.successful(null)
      }

      val result = Source(0 to 102).via(flow).runWith(Sink.seq)

      result.futureValue should contain.only("0", "100", "101", "102")
    }

    "complete without emitting any element after a sequence of nulls only" in {
      val flow = Flow[Int].mapAsyncUnordered[String](1) { _ =>
        Future.successful(null)
      }

      val result = Source(0 to 200).via(flow).runWith(Sink.seq)

      result.futureValue shouldBe empty
    }

    "complete stage if future with null result is completed last" in {
      import system.dispatcher

      val flow = Flow[Int].mapAsyncUnordered[String](1) {
        case 2 =>
          Future {
            null
          }
        case x =>
          Future.successful(x.toString)
      }

      val result = Source(List(1, 2, 3)).via(flow).runWith(Sink.seq)

      result.futureValue should contain.only("1", "3")
    }

    "handle cancel properly" in {
      val pub = TestPublisher.manualProbe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()

      Source.fromPublisher(pub).mapAsyncUnordered(1)(Future.successful).runWith(Sink.fromSubscriber(sub))

      val upstream = pub.expectSubscription()
      upstream.expectRequest()

      sub.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }
}

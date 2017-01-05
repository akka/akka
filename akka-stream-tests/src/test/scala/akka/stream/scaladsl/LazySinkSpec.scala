/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.TimeoutException

import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.Supervision._
import akka.stream._
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.{ Promise, Future, Await }
import scala.concurrent.duration._

class LazySinkSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 1, maxSize = 1)
  implicit val materializer = ActorMaterializer(settings)

  val fallback = () ⇒ fail("Must not call fallback function")
  val ex = TE("")

  "A LazySink" must {
    "work in happy case" in assertAllStagesStopped {
      val futureProbe = Source(0 to 10).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ Future.successful(TestSink.probe[Int]), fallback))
      val probe = Await.result(futureProbe, 300.millis)
      probe.request(100)
      (0 to 10).foreach(probe.expectNext)
    }

    "work with slow sink init" in assertAllStagesStopped {
      val p = Promise[Sink[Int, Probe[Int]]]()
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ p.future, fallback))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      sourceProbe.expectNoMsg(200.millis)
      a[TimeoutException] shouldBe thrownBy { Await.result(futureProbe, 200.millis) }

      p.success(TestSink.probe[Int])
      val probe = Await.result(futureProbe, 300.millis)
      probe.request(100)
      probe.expectNext(0)
      (1 to 10).foreach(i ⇒ {
        sourceSub.sendNext(i)
        probe.expectNext(i)
      })
      sourceSub.sendComplete()
    }

    "complete when there was no elements in stream" in assertAllStagesStopped {
      val futureProbe = Source.empty.runWith(Sink.lazyInit[Int, Future[Int]](_ ⇒ Future.successful(Sink.fold[Int, Int](0)(_ + _)), () ⇒ Future.successful(0)))
      val futureResult = Await.result(futureProbe, 300.millis)
      Await.result(futureResult, 300.millis) should ===(0)
    }

    "complete normally when upstream is completed" in assertAllStagesStopped {
      val futureProbe = Source.single(1).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ Future.successful(TestSink.probe[Int]), fallback))
      val futureResult = Await.result(futureProbe, 300.millis)
      futureResult.request(1)
        .expectNext(1)
        .expectComplete()
    }

    "failed gracefully when sink factory method failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ throw ex, fallback))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectCancellation()
      a[RuntimeException] shouldBe thrownBy { Await.result(futureProbe, 300.millis) }
    }

    "failed gracefully when upstream failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ Future.successful(TestSink.probe[Int]), fallback))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      val probe = Await.result(futureProbe, 300.millis)
      probe.request(1)
        .expectNext(0)
      sourceSub.sendError(ex)
      probe.expectError(ex)
    }

    "failed gracefully when factory future failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ Future.failed(ex), fallback)
        .withAttributes(supervisionStrategy(stoppingDecider)))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      a[TE] shouldBe thrownBy { Await.result(futureProbe, 300.millis) }
    }

    "cancel upstream when internal sink is cancelled" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInit[Int, Probe[Int]](_ ⇒ Future.successful(TestSink.probe[Int]), fallback))
      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      val probe = Await.result(futureProbe, 300.millis)
      probe.request(1)
        .expectNext(0)
      probe.cancel()
      sourceSub.expectCancellation()
    }

    "continue if supervision is resume" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInit[Int, Probe[Int]](a ⇒
        if (a == 0) throw ex else Future.successful(TestSink.probe[Int]), fallback)
        .withAttributes(supervisionStrategy(resumingDecider)))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(1)
      val probe = Await.result(futureProbe, 300.millis)
      probe.request(1)
      probe.expectNext(1)
      probe.cancel()
    }

    "fail future when zero throws exception" in assertAllStagesStopped {
      val futureProbe = Source.empty.runWith(Sink.lazyInit[Int, Future[Int]](_ ⇒ Future.successful(Sink.fold[Int, Int](0)(_ + _)), () ⇒ throw ex))
      a[TE] shouldBe thrownBy { Await.result(futureProbe, 300.millis) }
    }
  }

}

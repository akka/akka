/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._

class LazySinkSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 1, maxSize = 1)
  implicit val materializer = ActorMaterializer(settings)

  val ex = TE("")

  "A LazySink" must {
    "work in happy case" in assertAllStagesStopped {
      val futureProbe = Source(0 to 10).runWith(Sink.lazyInitAsync(() ⇒ Future.successful(TestSink.probe[Int])))
      val probe = Await.result(futureProbe, remainingOrDefault).get
      probe.request(100)
      (0 to 10).foreach(probe.expectNext)
    }

    "work with slow sink init" in assertAllStagesStopped {
      val p = Promise[Sink[Int, Probe[Int]]]()
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInitAsync(() ⇒ p.future))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      sourceProbe.expectNoMsg(200.millis)
      a[TimeoutException] shouldBe thrownBy { Await.result(futureProbe, remainingOrDefault) }

      p.success(TestSink.probe[Int])
      val probe = Await.result(futureProbe, remainingOrDefault).get
      probe.request(100)
      probe.expectNext(0)
      (1 to 10).foreach(i ⇒ {
        sourceSub.sendNext(i)
        probe.expectNext(i)
      })
      sourceSub.sendComplete()
    }

    "complete when there was no elements in stream" in assertAllStagesStopped {
      val futureProbe = Source.empty.runWith(Sink.lazyInitAsync(() ⇒ Future.successful(Sink.fold[Int, Int](0)(_ + _))))
      val futureResult = Await.result(futureProbe, remainingOrDefault)
      futureResult should ===(None)
    }

    "complete normally when upstream is completed" in assertAllStagesStopped {
      val futureProbe = Source.single(1).runWith(Sink.lazyInitAsync(() ⇒ Future.successful(TestSink.probe[Int])))
      val futureResult = Await.result(futureProbe, remainingOrDefault).get
      futureResult.request(1)
        .expectNext(1)
        .expectComplete()
    }

    "failed gracefully when sink factory method failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInitAsync[Int, Probe[Int]](() ⇒ throw ex))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectCancellation()
      a[RuntimeException] shouldBe thrownBy { Await.result(futureProbe, remainingOrDefault) }
    }

    "failed gracefully when upstream failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInitAsync(() ⇒ Future.successful(TestSink.probe[Int])))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      val probe = Await.result(futureProbe, remainingOrDefault).get
      probe.request(1)
        .expectNext(0)
      sourceSub.sendError(ex)
      probe.expectError(ex)
    }

    "fail gracefully when factory future failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInitAsync(() ⇒ Future.failed(ex)))

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      a[TE] shouldBe thrownBy { Await.result(futureProbe, remainingOrDefault) }
    }

    "cancel upstream when internal sink is cancelled" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val futureProbe = Source.fromPublisher(sourceProbe).runWith(Sink.lazyInitAsync(() ⇒ Future.successful(TestSink.probe[Int])))
      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      val probe = Await.result(futureProbe, remainingOrDefault).get
      probe.request(1)
        .expectNext(0)
      probe.cancel()
      sourceSub.expectCancellation()
    }

    "fail correctly when materialization of inner sink fails" in assertAllStagesStopped {
      val matFail = TE("fail!")
      object FailingInnerMat extends GraphStage[SinkShape[String]] {
        val in = Inlet[String]("in")
        val shape = SinkShape(in)
        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          throw matFail
        }
      }

      val result = Source(List("whatever"))
        .runWith(
          Sink.lazyInitAsync[String, NotUsed](
            () ⇒ { println("create sink"); Future.successful(Sink.fromGraph(FailingInnerMat)) }))

      result.failed.futureValue should ===(matFail)
    }
  }

}

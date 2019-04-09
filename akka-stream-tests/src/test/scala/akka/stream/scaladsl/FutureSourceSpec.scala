/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.{ CompletableFuture, TimeUnit }

import akka.stream._
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue }
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.testkit.TestLatch

import scala.concurrent.{ Await, Future, Promise }

class FutureSourceSpec extends StreamSpec {

  implicit val materializer = ActorMaterializer()
  implicit def ec = system.dispatcher

  val underlying: Source[Int, String] =
    Source(List(1, 2, 3)).mapMaterializedValue(_ => "foo")

  "Source.fromFuture" must {

    "emit the elements of the already successful future source" in assertAllStagesStopped {
      val (sourceMatVal, sinkMatVal) =
        Source.fromFutureSource(Future.successful(underlying)).toMat(Sink.seq)(Keep.both).run()

      // should complete as soon as inner source has been materialized
      sourceMatVal.futureValue should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "emit no elements before the future of source successful" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      val sourcePromise = Promise[Source[Int, String]]()
      val p = Source.fromFutureSource(sourcePromise.future).runWith(Sink.asPublisher(true)).subscribe(c)
      val sub = c.expectSubscription()
      import scala.concurrent.duration._
      c.expectNoMsg(100.millis)
      sub.request(3)
      c.expectNoMsg(100.millis)
      sourcePromise.success(underlying)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "emit the elements of the future source" in assertAllStagesStopped {

      val sourcePromise = Promise[Source[Int, String]]()
      val (sourceMatVal, sinkMatVal) =
        Source.fromFutureSource(sourcePromise.future).toMat(Sink.seq)(Keep.both).run()
      sourcePromise.success(underlying)
      // should complete as soon as inner source has been materialized
      sourceMatVal.futureValue should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "emit the elements from a source in a completion stage" in assertAllStagesStopped {
      val (sourceMatVal, sinkMatVal) =
        Source
          .fromSourceCompletionStage(
            // can't be inferred
            CompletableFuture.completedFuture[Graph[SourceShape[Int], String]](underlying))
          .toMat(Sink.seq)(Keep.both)
          .run()

      sourceMatVal.toCompletableFuture.get(remainingOrDefault.toMillis, TimeUnit.MILLISECONDS) should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "handle downstream cancelling before the underlying Future completes" in assertAllStagesStopped {
      val sourcePromise = Promise[Source[Int, String]]()

      val probe = TestSubscriber.probe[Int]()
      val sourceMatVal =
        Source.fromFutureSource(sourcePromise.future).toMat(Sink.fromSubscriber(probe))(Keep.left).run()

      // wait for cancellation to occur
      probe.ensureSubscription()
      probe.request(1)
      probe.cancel()

      // try to avoid a race between probe cancel and completing the promise
      Thread.sleep(100)

      // even though canceled the underlying matval should arrive
      sourcePromise.success(underlying)
      val failure = sourceMatVal.failed.futureValue
      failure shouldBe a[StreamDetachedException]
      failure.getMessage should ===("Stream cancelled before Source Future completed")
    }

    "fail if the underlying Future is failed" in assertAllStagesStopped {
      val failure = TE("foo")
      val underlying = Future.failed[Source[Int, String]](failure)
      val (sourceMatVal, sinkMatVal) = Source.fromFutureSource(underlying).toMat(Sink.seq)(Keep.both).run()
      sourceMatVal.failed.futureValue should ===(failure)
      sinkMatVal.failed.futureValue should ===(failure)
    }

    "fail as the underlying Future fails after outer source materialization" in assertAllStagesStopped {
      val failure = TE("foo")
      val sourcePromise = Promise[Source[Int, String]]()
      val materializationLatch = TestLatch(1)
      val (sourceMatVal, sinkMatVal) =
        Source
          .fromFutureSource(sourcePromise.future)
          .mapMaterializedValue { value =>
            materializationLatch.countDown()
            value
          }
          .toMat(Sink.seq)(Keep.both)
          .run()

      // we don't know that materialization completed yet (this is still a bit racy)
      Await.ready(materializationLatch, remainingOrDefault)
      Thread.sleep(100)
      sourcePromise.failure(failure)

      sourceMatVal.failed.futureValue should ===(failure)
      sinkMatVal.failed.futureValue should ===(failure)
    }

    "fail as the underlying Future fails after outer source materialization with no demand" in assertAllStagesStopped {
      val failure = TE("foo")
      val sourcePromise = Promise[Source[Int, String]]()
      val testProbe = TestSubscriber.probe[Int]()
      val sourceMatVal =
        Source.fromFutureSource(sourcePromise.future).to(Sink.fromSubscriber(testProbe)).run()

      testProbe.expectSubscription()
      sourcePromise.failure(failure)

      sourceMatVal.failed.futureValue should ===(failure)
    }

    "handle back-pressure when the future completes" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()
      val publisher = TestPublisher.probe[Int]()

      val sourcePromise = Promise[Source[Int, String]]()

      val matVal = Source.fromFutureSource(sourcePromise.future).to(Sink.fromSubscriber(subscriber)).run()

      subscriber.ensureSubscription()

      sourcePromise.success(Source.fromPublisher(publisher).mapMaterializedValue(_ => "woho"))

      // materialized value completes but still no demand
      matVal.futureValue should ===("woho")

      // then demand and let an element through to see it works
      subscriber.ensureSubscription()
      subscriber.request(1)
      publisher.expectRequest()
      publisher.sendNext(1)
      subscriber.expectNext(1)
      publisher.sendComplete()
      subscriber.expectComplete()
    }

    "carry through cancellation to later materialized source" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()
      val publisher = TestPublisher.probe[Int]()

      val sourcePromise = Promise[Source[Int, String]]()

      val matVal = Source.fromFutureSource(sourcePromise.future).to(Sink.fromSubscriber(subscriber)).run()

      subscriber.ensureSubscription()

      sourcePromise.success(Source.fromPublisher(publisher).mapMaterializedValue(_ => "woho"))

      // materialized value completes but still no demand
      matVal.futureValue should ===("woho")

      // cancelling the outer source should carry through to the internal one
      subscriber.ensureSubscription()
      subscriber.cancel()
      publisher.expectCancellation()
    }

    class FailingMatGraphStage extends GraphStageWithMaterializedValue[SourceShape[Int], String] {
      val out = Outlet[Int]("whatever")
      override val shape: SourceShape[Int] = SourceShape(out)
      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, String) = {
        throw TE("INNER_FAILED")
      }

    }

    "fail when the future source materialization fails" in assertAllStagesStopped {
      val inner = Future.successful(Source.fromGraph(new FailingMatGraphStage))
      val (innerSourceMat: Future[String], outerSinkMat: Future[Seq[Int]]) =
        Source.fromFutureSource(inner).toMat(Sink.seq)(Keep.both).run()

      outerSinkMat.failed.futureValue should ===(TE("INNER_FAILED"))
      innerSourceMat.failed.futureValue should ===(TE("INNER_FAILED"))
    }
  }

  "Source.future" must {

    "emit an initially completed value" in {
      val result = Source.future(Future.successful(1)).runWith(Sink.seq)
      result.futureValue should ===(Seq(1))
    }

    "emit a later completed value" in {
      val promise = Promise[Int]()
      val result = Source.future(promise.future).runWith(Sink.seq)

      promise.success(1)
      result.futureValue should ===(Seq(1))
    }

    "fail on a initially failed future" in {
      val failure = TE("boom")
      val result = Source.future[Int](Future.failed(failure)).runWith(Sink.seq)
      result.failed.futureValue should ===(failure)
    }

    "fail on a later failed future" in {
      val promise = Promise[Int]()
      val result = Source.future(promise.future).runWith(Sink.seq)

      val failure = TE("boom")
      promise.failure(failure)
      result.failed.futureValue should ===(failure)
    }

  }

  "Source.fromSourceCompletionStage" must {
    "emit the elements from a source in a completion stage" in assertAllStagesStopped {
      val (sourceMatVal, sinkMatVal) =
        Source
          .fromSourceCompletionStage(
            // can't be inferred
            CompletableFuture.completedFuture[Graph[SourceShape[Int], String]](underlying))
          .toMat(Sink.seq)(Keep.both)
          .run()

      sourceMatVal.toCompletableFuture.get(remainingOrDefault.toMillis, TimeUnit.MILLISECONDS) should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }
  }

  "Source.future" must {

    val underlying: Source[Int, String] =
      Source(List(1, 2, 3)).mapMaterializedValue(_ => "foo")

    "emit the elements of the already successful future source" in assertAllStagesStopped {
      val (sourceMatVal, sinkMatVal) =
        Source.futureSource(Future.successful(underlying)).toMat(Sink.seq)(Keep.both).run()

      // should complete as soon as inner source has been materialized
      sourceMatVal.futureValue should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "emit no elements before the future of source successful" in assertAllStagesStopped {
      val c = TestSubscriber.manualProbe[Int]()
      val sourcePromise = Promise[Source[Int, String]]()
      val p = Source.futureSource(sourcePromise.future).runWith(Sink.asPublisher(true)).subscribe(c)
      val sub = c.expectSubscription()
      import scala.concurrent.duration._
      c.expectNoMsg(100.millis)
      sub.request(3)
      c.expectNoMsg(100.millis)
      sourcePromise.success(underlying)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "emit the elements of the future source" in assertAllStagesStopped {

      val sourcePromise = Promise[Source[Int, String]]()
      val (sourceMatVal, sinkMatVal) =
        Source.futureSource(sourcePromise.future).toMat(Sink.seq)(Keep.both).run()
      sourcePromise.success(underlying)
      // should complete as soon as inner source has been materialized
      sourceMatVal.futureValue should ===("foo")
      sinkMatVal.futureValue should ===(List(1, 2, 3))
    }

    "handle downstream cancelling before the underlying Future completes" in assertAllStagesStopped {
      val sourcePromise = Promise[Source[Int, String]]()

      val probe = TestSubscriber.probe[Int]()
      val sourceMatVal =
        Source.futureSource(sourcePromise.future).toMat(Sink.fromSubscriber(probe))(Keep.left).run()

      // wait for cancellation to occur
      probe.ensureSubscription()
      probe.request(1)
      probe.cancel()

      // try to avoid a race between probe cancel and completing the promise
      Thread.sleep(100)

      // even though canceled the underlying matval should arrive
      sourcePromise.success(underlying)
      val failure = sourceMatVal.failed.futureValue
      failure shouldBe a[StreamDetachedException]
      failure.getMessage should ===("Stream cancelled before Source Future completed")
    }

    "fail if the underlying Future is failed" in assertAllStagesStopped {
      val failure = TE("foo")
      val underlying = Future.failed[Source[Int, String]](failure)
      val (sourceMatVal, sinkMatVal) = Source.futureSource(underlying).toMat(Sink.seq)(Keep.both).run()
      sourceMatVal.failed.futureValue should ===(failure)
      sinkMatVal.failed.futureValue should ===(failure)
    }

    "fail as the underlying Future fails after outer source materialization" in assertAllStagesStopped {
      val failure = TE("foo")
      val sourcePromise = Promise[Source[Int, String]]()
      val materializationLatch = TestLatch(1)
      val (sourceMatVal, sinkMatVal) =
        Source
          .futureSource(sourcePromise.future)
          .mapMaterializedValue { value =>
            materializationLatch.countDown()
            value
          }
          .toMat(Sink.seq)(Keep.both)
          .run()

      // we don't know that materialization completed yet (this is still a bit racy)
      Await.ready(materializationLatch, remainingOrDefault)
      Thread.sleep(100)
      sourcePromise.failure(failure)

      sourceMatVal.failed.futureValue should ===(failure)
      sinkMatVal.failed.futureValue should ===(failure)
    }

    "fail as the underlying Future fails after outer source materialization with no demand" in assertAllStagesStopped {
      val failure = TE("foo")
      val sourcePromise = Promise[Source[Int, String]]()
      val testProbe = TestSubscriber.probe[Int]()
      val sourceMatVal =
        Source.futureSource(sourcePromise.future).to(Sink.fromSubscriber(testProbe)).run()

      testProbe.expectSubscription()
      sourcePromise.failure(failure)

      sourceMatVal.failed.futureValue should ===(failure)
    }

    "handle back-pressure when the future completes" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()
      val publisher = TestPublisher.probe[Int]()

      val sourcePromise = Promise[Source[Int, String]]()

      val matVal = Source.futureSource(sourcePromise.future).to(Sink.fromSubscriber(subscriber)).run()

      subscriber.ensureSubscription()

      sourcePromise.success(Source.fromPublisher(publisher).mapMaterializedValue(_ => "woho"))

      // materialized value completes but still no demand
      matVal.futureValue should ===("woho")

      // then demand and let an element through to see it works
      subscriber.ensureSubscription()
      subscriber.request(1)
      publisher.expectRequest()
      publisher.sendNext(1)
      subscriber.expectNext(1)
      publisher.sendComplete()
      subscriber.expectComplete()
    }

    "carry through cancellation to later materialized source" in assertAllStagesStopped {
      val subscriber = TestSubscriber.probe[Int]()
      val publisher = TestPublisher.probe[Int]()

      val sourcePromise = Promise[Source[Int, String]]()

      val matVal = Source.futureSource(sourcePromise.future).to(Sink.fromSubscriber(subscriber)).run()

      subscriber.ensureSubscription()

      sourcePromise.success(Source.fromPublisher(publisher).mapMaterializedValue(_ => "woho"))

      // materialized value completes but still no demand
      matVal.futureValue should ===("woho")

      // cancelling the outer source should carry through to the internal one
      subscriber.ensureSubscription()
      subscriber.cancel()
      publisher.expectCancellation()
    }

    class FailingMatGraphStage extends GraphStageWithMaterializedValue[SourceShape[Int], String] {
      val out = Outlet[Int]("whatever")
      override val shape: SourceShape[Int] = SourceShape(out)
      override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, String) = {
        throw TE("INNER_FAILED")
      }

    }

    "fail when the future source materialization fails" in assertAllStagesStopped {
      val inner = Future.successful(Source.fromGraph(new FailingMatGraphStage))
      val (innerSourceMat: Future[String], outerSinkMat: Future[Seq[Int]]) =
        Source.futureSource(inner).toMat(Sink.seq)(Keep.both).run()

      outerSinkMat.failed.futureValue should ===(TE("INNER_FAILED"))
      innerSourceMat.failed.futureValue should ===(TE("INNER_FAILED"))
    }
  }
}

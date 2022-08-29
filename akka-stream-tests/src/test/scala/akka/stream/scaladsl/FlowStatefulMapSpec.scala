/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.Done
import akka.stream.AbruptStageTerminationException
import akka.stream.ActorAttributes
import akka.stream.ActorMaterializer
import akka.stream.Supervision
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource

import scala.annotation.nowarn
import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Success
import scala.util.control.NoStackTrace

class FlowStatefulMapSpec extends StreamSpec {

  val ex = new Exception("TEST") with NoStackTrace

  "A StatefulMap" must {
    "work in the happy case" in {
      val sinkProb = Source(List(1, 2, 3, 4, 5))
        .statefulMap(() => 0)((agg, elem) => {
          (agg + elem, (agg, elem))
        }, _ => None)
        .runWith(TestSink.probe[(Int, Int)])
      sinkProb.expectSubscription().request(6)
      sinkProb
        .expectNext((0, 1))
        .expectNext((1, 2))
        .expectNext((3, 3))
        .expectNext((6, 4))
        .expectNext((10, 5))
        .expectComplete()
    }

    "can remember the state when complete" in {
      val sinkProb = Source(1 to 10)
        .statefulMap(() => List.empty[Int])(
          (state, elem) => {
            //grouped 3 elements into a list
            val newState = elem :: state
            if (newState.size == 3)
              (Nil, newState.reverse)
            else
              (newState, Nil)
          },
          state => Some(state.reverse))
        .mapConcat(identity)
        .runWith(TestSink.probe[Int])
      sinkProb.expectSubscription().request(10)
      sinkProb.expectNextN(List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).expectComplete()
    }

    "be able to resume" in {
      val testSink = Source(List(1, 2, 3, 4, 5))
        .statefulMap(() => 0)((agg, elem) => {
          if (elem % 2 == 0)
            throw ex
          else
            (agg + elem, (agg, elem))
        }, _ => None)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.resumingDecider))
        .runWith(TestSink.probe[(Int, Int)])

      testSink.expectSubscription().request(5)
      testSink.expectNext((0, 1)).expectNext((1, 3)).expectNext((4, 5)).expectComplete()
    }

    "be able to restart" in {
      val testSink = Source(List(1, 2, 3, 4, 5))
        .statefulMap(() => 0)((agg, elem) => {
          if (elem % 3 == 0)
            throw ex
          else
            (agg + elem, (agg, elem))
        }, _ => None)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.restartingDecider))
        .runWith(TestSink.probe[(Int, Int)])

      testSink.expectSubscription().request(5)
      testSink.expectNext((0, 1)).expectNext((1, 2)).expectNext((0, 4)).expectNext((4, 5)).expectComplete()
    }

    "be able to stop" in {
      val testSink = Source(List(1, 2, 3, 4, 5))
        .statefulMap(() => 0)((agg, elem) => {
          if (elem % 3 == 0)
            throw ex
          else
            (agg + elem, (agg, elem))
        }, _ => None)
        .withAttributes(ActorAttributes.supervisionStrategy(Supervision.stoppingDecider))
        .runWith(TestSink.probe[(Int, Int)])

      testSink.expectSubscription().request(5)
      testSink.expectNext((0, 1)).expectNext((1, 2)).expectError(ex)
    }

    "fail on upstream failure" in {
      val (testSource, testSink) = TestSource
        .probe[Int]
        .statefulMap(() => 0)((agg, elem) => {
          (agg + elem, (agg, elem))
        }, _ => None)
        .toMat(TestSink.probe[(Int, Int)])(Keep.both)
        .run()

      testSink.expectSubscription().request(5)
      testSource.sendNext(1)
      testSink.expectNext((0, 1))
      testSource.sendNext(2)
      testSink.expectNext((1, 2))
      testSource.sendNext(3)
      testSink.expectNext((3, 3))
      testSource.sendNext(4)
      testSink.expectNext((6, 4))
      testSource.sendError(ex)
      testSink.expectError(ex)
    }

    "defer upstream failure and remember state" in {
      val (testSource, testSink) = TestSource
        .probe[Int]
        .statefulMap(() => 0)((agg, elem) => { (agg + elem, (agg, elem)) }, (state: Int) => Some((state, -1)))
        .toMat(TestSink.probe[(Int, Int)])(Keep.both)
        .run()

      testSink.expectSubscription().request(5)
      testSource.sendNext(1)
      testSink.expectNext((0, 1))
      testSource.sendNext(2)
      testSink.expectNext((1, 2))
      testSource.sendNext(3)
      testSink.expectNext((3, 3))
      testSource.sendNext(4)
      testSink.expectNext((6, 4))
      testSource.sendError(ex)
      testSink.expectNext((10, -1))
      testSink.expectError(ex)
    }

    "cancel upstream when downstream cancel" in {
      val promise = Promise[Done]()
      val testSource = TestSource
        .probe[Int]
        .statefulMap(() => 100)((agg, elem) => {
          (agg + elem, (agg, elem))
        }, (state: Int) => {
          promise.complete(Success(Done))
          Some((state, -1))
        })
        .toMat(Sink.cancelled)(Keep.left)
        .run()
      testSource.expectSubscription().expectCancellation()
      Await.result(promise.future, 3.seconds) shouldBe Done
    }

    "cancel upstream when downstream fail" in {
      val promise = Promise[Done]()
      val testProb = TestSubscriber.probe[(Int, Int)]()
      val testSource = TestSource
        .probe[Int]
        .statefulMap(() => 100)((agg, elem) => {
          (agg + elem, (agg, elem))
        }, (state: Int) => {
          promise.complete(Success(Done))
          Some((state, -1))
        })
        .toMat(Sink.fromSubscriber(testProb))(Keep.left)
        .run()
      testProb.cancel(ex)
      testSource.expectCancellationWithCause(ex)
      Await.result(promise.future, 3.seconds) shouldBe Done
    }

    "call its onComplete callback on abrupt materializer termination" in {
      @nowarn("msg=deprecated")
      val mat = ActorMaterializer()
      val promise = Promise[Done]()

      val matVal = Source
        .single(1)
        .statefulMap(() => -1)((_, elem) => (elem, elem), _ => {
          promise.complete(Success(Done))
          None
        })
        .runWith(Sink.never)(mat)
      mat.shutdown()
      matVal.failed.futureValue shouldBe a[AbruptStageTerminationException]
      Await.result(promise.future, 3.seconds) shouldBe Done
    }

    "call its onComplete callback when stop" in {
      val promise = Promise[Done]()
      Source
        .single(1)
        .statefulMap(() => -1)((_, elem) => {
          throw ex
          (elem, elem)
        }, _ => {
          promise.complete(Success(Done))
          None
        })
        .runWith(Sink.ignore)
      Await.result(promise.future, 3.seconds) shouldBe Done
    }

    "be able to be used as zipWithIndex" in {
      Source(List("A", "B", "C", "D"))
        .statefulMap(() => 0L)((index, elem) => (index + 1, (elem, index)), _ => None)
        .runWith(TestSink.probe[(String, Long)])
        .request(4)
        .expectNext(("A", 0L))
        .expectNext(("B", 1L))
        .expectNext(("C", 2L))
        .expectNext(("D", 3L))
        .expectComplete()
    }

    "be able to be used as bufferUntilChanged" in {
      val sink = TestSink.probe[List[String]]
      Source("A" :: "B" :: "B" :: "C" :: "C" :: "C" :: "D" :: Nil)
        .statefulMap(() => List.empty[String])(
          (buffer, elem) =>
            buffer match {
              case head :: _ if head != elem => (elem :: Nil, buffer)
              case _                         => (elem :: buffer, Nil)
            },
          buffer => Some(buffer))
        .filter(_.nonEmpty)
        .alsoTo(Sink.foreach(println))
        .runWith(sink)
        .request(4)
        .expectNext(List("A"))
        .expectNext(List("B", "B"))
        .expectNext(List("C", "C", "C"))
        .expectNext(List("D"))
        .expectComplete()
    }

    "be able to be used as distinctUntilChanged" in {
      Source("A" :: "B" :: "B" :: "C" :: "C" :: "C" :: "D" :: Nil)
        .statefulMap(() => Option.empty[String])(
          (lastElement, elem) =>
            lastElement match {
              case Some(head) if head == elem => (Some(elem), None)
              case _                          => (Some(elem), Some(elem))
            },
          _ => None)
        .collect({ case Some(elem) => elem })
        .runWith(TestSink.probe[String])
        .request(4)
        .expectNext("A")
        .expectNext("B")
        .expectNext("C")
        .expectNext("D")
        .expectComplete()
    }
  }
}

/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.impl.RetryFlowCoordinator
import akka.stream.{ BidiShape, FlowShape, Graph, KillSwitches }
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber, Utils }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import org.scalatest.matchers.{ MatchResult, Matcher }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class RetryFlowSpec extends StreamSpec() with CustomMatchers {

  final val FailedElem: Try[Int] = Failure(new Exception("cooked failure"))

  def failEvenValuesAndIncFlow[T]: Flow[(Int, T), (Try[Int], T), NotUsed] = Flow.fromFunction {
    case (i, j) if i % 2 == 0 => (FailedElem, j)
    case (i, j)               => (Success(i + 1), j)
  }

  def failEvenValuesFlow[T]: Flow[(Int, T), (Try[Int], T), NotUsed] = Flow.fromFunction {
    case (i, j) if i % 2 == 0 => (FailedElem, j)
    case (i, j)               => (Success(i), j)
  }

  def failAllValuesFlow[T]: Flow[(Int, T), (Try[Int], T), NotUsed] = Flow.fromFunction {
    case (i, j) => (FailedElem, j)
  }

  /** increments the context with every failure */
  def retryFailedValues[InData, OutData](in: InData, t: Try[OutData], ctx: Int): Option[(InData, Int)] =
    (in, t, ctx) match {
      case (in, Failure(_), ctx) => Some((in, ctx + 1))
      case _                     => None
    }

  def incrementFailedValues[OutData](in: Int, t: Try[OutData], ctx: Int): Option[(Int, Int)] =
    (in, t, ctx) match {
      case (in, Failure(_), ctx) => Some((in + 1, ctx + 1))
      case _                     => None
    }

  "RetryFlow.withBackoff" should {

    "send elements through" in {
      val sink =
        Source(List(13, 17, 19, 23, 27).map(_ -> 0))
          .via(
            RetryFlow.withBackoff(10.millis, 5.second, 0d, maxRetries = 3, failEvenValuesFlow[Int])(retryFailedValues))
          .runWith(Sink.seq)
      sink.futureValue should contain inOrderElementsOf List(
        Success(13) -> 0,
        Success(17) -> 0,
        Success(19) -> 0,
        Success(23) -> 0,
        Success(27) -> 0)
    }

    "send failures through (after retrying)" in {
      val maxRetries = 2
      val sink =
        Source(List(13, 17).map(_ -> 0))
          .via(RetryFlow.withBackoff(1.millis, 5.millis, 0d, maxRetries, failAllValuesFlow[Int])(retryFailedValues))
          .runWith(Sink.seq)
      sink.futureValue should contain inOrderElementsOf List(FailedElem -> maxRetries, FailedElem -> maxRetries)
    }

    "send elements through (with retrying)" in {
      val sink =
        Source(List(12, 13, 14).map(_ -> 0))
          .via(RetryFlow.withBackoff(1.millis, 5.millis, 0d, maxRetries = 3, failEvenValuesFlow[Int])(
            incrementFailedValues))
          .runWith(Sink.seq)
      sink.futureValue should contain inOrderElementsOf List(Success(13) -> 1, Success(13) -> 0, Success(15) -> 1)
    }

    "allow retrying a successful element" in {
      val flow = Flow.fromFunction[(Int, Int), (Try[Int], Int)] {
        case (i, ctx) => Success(i) -> ctx
      }

      //#retry-success
      val retryFlow = RetryFlow.withBackoff(10.millis, 5.second, 0, 3, flow) {
        case (_, Success(i), ctx) if i < 5 => Some((i + 1) -> (ctx + 1))
        case _                             => None
      }
      //#retry-success

      val (source, sink) = TestSource.probe[Int].map(_ -> 0).via(retryFlow).toMat(TestSink.probe)(Keep.both).run()

      sink.request(4)

      source.sendNext(5)
      sink.expectNext(Success(5) -> 0)

      source.sendNext(2)
      sink.expectNext(Success(5) -> 3)

      source.sendComplete()
      sink.expectComplete()
    }

    "support flow with context" in {
      val flow = Flow[Int].asFlowWithContext((i: Int, _: Int) => i)(identity).map {
        case i if i > 0 => Failure(new Error("i is larger than 0"))
        case i          => Success(i)
      }

      val (source, sink) = TestSource
        .probe[Int]
        .asSourceWithContext(identity)
        .via(RetryFlow.withBackoffAndContext(10.millis, 5.second, 0, 10, flow) {
          case (_, Failure(_), ctx) if ctx > 0 => Some((ctx / 2, ctx / 2))
          case _                               => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(1)

      source.sendNext(8)
      sink.expectNext((Success(0), 0))

      source.sendComplete()
      sink.expectComplete()
    }
  }

  "Backing off" should {
    "have min backoff" in {
      val minBackoff = 200.millis
      val (source, sink) = TestSource
        .probe[Int]
        .map(_ -> 0)
        .via(RetryFlow.withBackoff(minBackoff, 5.second, 0d, 3, failEvenValuesFlow[Int])(incrementFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext(80.millis, Success(1) -> 0)

      source.sendNext(2)
      sink.expectNoMessage(minBackoff)
      sink.expectNext(Success(3) -> 1)
    }

    "use min backoff for every try" in {
      val minBackoff = 50.millis
      val maxRetries = 3
      val (source, sink) = TestSource
        .probe[Int]
        .map(_ -> 0)
        .via(RetryFlow.withBackoff(minBackoff, 5.second, 0d, maxRetries, failAllValuesFlow[Int])(retryFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(1)

      source.sendNext(10)
      sink.expectNoMessage(minBackoff * maxRetries)
      sink.expectNext(FailedElem -> maxRetries)
    }

    "exponentially backoff between retries" in {
      val NumRetries = 7

      val nanoTimeOffset = System.nanoTime()
      case class State(counter: Int, retriedAt: List[Long])

      val flow = Flow.fromFunction[(Int, State), (Try[Int], State)] {
        case (i, j) if i % NumRetries == 0 => (Success(i), j)
        case (_, State(counter, retriedAt)) =>
          (FailedElem, State(counter + 1, System.nanoTime - nanoTimeOffset :: retriedAt))
      }

      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, State(i, Nil)))
        .via(RetryFlow.withBackoff(10.millis, 5.second, 0, 50, flow) {
          case (_, Failure(_), s @ State(counter, _)) => Some(counter -> s)
          case _                                      => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(1)

      source.sendNext(1)
      val (result, State(_, retriedAt)) = sink.expectNext()

      result shouldBe Success(NumRetries)
      val timesBetweenRetries = retriedAt
        .sliding(2)
        .collect {
          case before :: after :: Nil => before - after
        }
        .toIndexedSeq

      timesBetweenRetries.reverse should strictlyIncrease

      source.sendComplete()
      sink.expectComplete()
    }

  }

  "Aborting" should {
    "tolerate killswitch abort after start" in {
      //#retry-failure
      val retryFlow = RetryFlow.withBackoff(10.millis, 5.second, 0, 3, failEvenValuesAndIncFlow[Int]) {
        case (_, Failure(_), x) => Some((x + 1, x))
        case _                  => None
      }
      //#retry-failure

      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i))
        .via(retryFlow)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext((Success(2), 1))

      source.sendNext(2)
      sink.expectNext((Success(4), 2))

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(10.millis, 5.second, 0, 3, failEvenValuesAndIncFlow[Int]) {
          case (_, Failure(_), x) => Some((x + 1, x))
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort before start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(10.millis, 5.second, 0, 3, failEvenValuesAndIncFlow[Int]) {
          case (_, Failure(_), x) => Some((x + 1, x))
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(FailedElem.failed.get)

      sink.request(1)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow after start" in {
      val innerFlow =
        failEvenValuesAndIncFlow[Int]
          .via(Utils.delayCancellation(10.seconds))
          .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(10.millis, 5.second, 0, 3, innerFlow) {
          case (_, Failure(_), x) => Some((x + 1, x))
          case _                  => None
        })(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext((Success(2), 1))

      source.sendNext(2)
      sink.expectNext((Success(4), 2))

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow on start" in {
      val innerFlow =
        failEvenValuesAndIncFlow[Int]
          .via(Utils.delayCancellation(10.seconds))
          .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(10.millis, 5.second, 0, 3, innerFlow) {
          case (_, Failure(_), x) => Some((x + 1, x))
          case _                  => None
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow before start" in {
      val innerFlow =
        failEvenValuesAndIncFlow[Int]
          .via(Utils.delayCancellation(10.seconds))
          .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(10.millis, 5.second, 0, 3, innerFlow) {
          case (_, Failure(_), x) => Some((x + 1, x))
          case _                  => None
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(FailedElem.failed.get)

      sink.request(1)
      sink.expectError(FailedElem.failed.get)
    }

    val alwaysFailingFlow = Flow.fromFunction[(Int, Int), (Try[Int], Int)] {
      case (_, j) => (FailedElem, j)
    }

    val alwaysRecoveringFunc: (Int, Try[Int], Int) => Option[(Int, Int)] = {
      case (_, Failure(_), i) => Some(i -> i)
      case _                  => None
    }

    val stuckForeverRetrying =
      RetryFlow.withBackoff(10.millis, 5.second, 0, 3, alwaysFailingFlow)(alwaysRecoveringFunc)

    "tolerate killswitch abort before the RetryFlow while on retry spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i))
        .via(stuckForeverRetrying)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNoMessage()

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow while on retry spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(
          RetryFlow.withBackoff(
            10.millis,
            5.second,
            0,
            3,
            alwaysFailingFlow
              .via(Utils.delayCancellation(10.seconds))
              .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right))(alwaysRecoveringFunc))(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort after the RetryFlow while on retry spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(stuckForeverRetrying)
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNoMessage()

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "finish only after processing all elements in stream" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, 0))
        .via(RetryFlow.withBackoff(10.millis, 5.second, 0, 3, failEvenValuesFlow[Int]) {
          case (_, Failure(_), x) => Some(1 -> (x + 1))
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      source.sendNext(3)
      source.sendNext(2)
      source.sendComplete()
      sink.expectNext(Success(1) -> 0)
      sink.expectNext(Success(3) -> 0)
      sink.expectNext(Success(1) -> 1)
      sink.expectComplete()
    }
  }

  "Coordinator" should {

    class ConstructBench[InData, UserState, OutData](
        retryWith: (InData, Try[OutData], UserState) => Option[(InData, UserState)]) {

      def setup() = {
        val retry: RetryFlowCoordinator[InData, UserState, OutData] =
          new RetryFlowCoordinator[InData, UserState, OutData](
            retryWith,
            minBackoff = 10.millis,
            maxBackoff = 1.second,
            0,
            3)

        val bidiFlow: BidiFlow[
          (InData, UserState),
          (InData, UserState),
          (Try[OutData], UserState),
          (Try[OutData], UserState),
          NotUsed] =
          BidiFlow.fromGraph(retry)

        val throughDangerousFlow: Flow[
          (InData, UserState),
          (Try[OutData], UserState),
          (TestSubscriber.Probe[(InData, UserState)], TestPublisher.Probe[(Try[OutData], UserState)])] =
          Flow.fromSinkAndSourceMat(TestSink.probe[(InData, UserState)], TestSource.probe[(Try[OutData], UserState)])(
            Keep.both)

        val inOutFlow: Flow[
          (InData, UserState),
          (Try[OutData], UserState),
          (TestSubscriber.Probe[(InData, UserState)], TestPublisher.Probe[(Try[OutData], UserState)])] =
          bidiFlow.joinMat(throughDangerousFlow)(Keep.right)

        val ((externalIn, (internalOut, internalIn)), externalOut) = TestSource
          .probe[(InData, UserState)]
          .viaMat(inOutFlow)(Keep.both)
          .toMat(TestSink.probe[(Try[OutData], UserState)])(Keep.both)
          .run()

        (externalIn, externalOut, internalIn, internalOut)
      }
      val (externalIn, externalOut, internalIn, internalOut) = setup()
    }

    type InData = String
    type UserCtx = Int
    type OutData = String

    "send successful elements accross" in new ConstructBench[InData, UserCtx, OutData]({
      val retryWith: (InData, Try[OutData], UserCtx) => Option[(InData, UserCtx)] = {
        case (_, Success(t), state) => None
        case (_, Failure(e), state) => ???
      }
      retryWith
    }) {
      // push element
      val elA = ("A", 123)
      externalIn.sendNext(elA)

      // let element go via retryable flow
      val elA2 = internalOut.requestNext()
      elA2._1 shouldBe elA._1
      elA2._2 shouldBe elA._2
      internalIn.sendNext((Success("result A"), 123))

      // expect result
      externalOut.requestNext() shouldBe (Success("result A"), 123)
    }

    "retry for a failure" in new ConstructBench({
      val retryWith: (InData, Try[OutData], UserCtx) => Option[(InData, UserCtx)] = {
        case (_, Success(t), state) => None
        case (_, Failure(e), state) => Some(("A'", 2))

      }
      retryWith
    }) {
      // demand on stream end
      externalOut.request(1)

      // push element
      val element1 = ("A", 1)
      externalIn.sendNext(element1)

      // let element go via retryable flow
      val try1 = internalOut.requestNext()
      try1._1 shouldBe element1._1
      try1._2 shouldBe element1._2
      internalIn.sendNext((Failure(new RuntimeException("boom")), 1))

      // let element go via retryable flow
      val try2 = internalOut.requestNext(3.seconds)
      try2._1 shouldBe "A'"
      try2._2 shouldBe 2
      internalIn.sendNext((Success("Ares"), 1))

      // expect result
      externalOut.requestNext() shouldBe (Success("Ares"), 1)
    }

    "allow to send two elements" in new ConstructBench({
      val retryWith: (InData, Try[OutData], UserCtx) => Option[(InData, UserCtx)] = {
        case (_, Success(t), state) => None
        case (_, Failure(e), state) => Some(("A'", 2))
      }
      retryWith
    }) {
      // demand on stream end
      externalOut.request(2)

      // push element
      val element1 = ("A", 1)
      externalIn.sendNext(element1)
      val element2 = ("B", 2)
      externalIn.sendNext(element2)

      // let element go via retryable flow
      val try1 = internalOut.requestNext()
      try1._1 shouldBe element1._1
      try1._2 shouldBe element1._2
      internalIn.sendNext((Failure(new RuntimeException("boom")), 1))

      // let element go via retryable flow
      val try2 = internalOut.requestNext()
      try2._1 shouldBe "A'"
      try2._2 shouldBe 2
      internalIn.sendNext((Success("Ares"), 1))

      // let element2 go via retryable flow
      val try2_1 = internalOut.requestNext()
      try2_1._1 shouldBe element2._1
      try2_1._2 shouldBe element2._2
      internalIn.sendNext((Success("Bres"), 1))

      // expect result
      externalOut.requestNext() shouldBe (Success("Ares"), 1)
      externalOut.requestNext() shouldBe (Success("Bres"), 1)
    }

  }

}

trait CustomMatchers {

  class StrictlyIncreasesMatcher() extends Matcher[Seq[Long]] {

    def apply(left: Seq[Long]): MatchResult = {
      val result = left.sliding(2).map(pair => pair.head < pair.last).reduceOption(_ && _).getOrElse(false)

      MatchResult(
        result,
        s"""Collection $left elements are not increasing strictly""",
        s"""Collection $left elements are increasing strictly""")
    }
  }

  def strictlyIncrease = new StrictlyIncreasesMatcher()
}

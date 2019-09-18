/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.KillSwitches
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber, Utils }
import org.scalatest.matchers.{ MatchResult, Matcher }

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class RetryFlowSpec extends StreamSpec() with CustomMatchers {

  final val FailedElem: Try[Int] = Failure(new Exception("cooked failure"))

  def noActionFlow[T, Ctx]: FlowWithContext[T, Ctx, T, Ctx, NotUsed] = FlowWithContext[T, Ctx]

  def failEvenValuesFlow[Ctx]: FlowWithContext[Int, Ctx, Try[Int], Ctx, NotUsed] =
    FlowWithContext.fromTuples(Flow.fromFunction {
      case (i, ctx) if i % 2 == 0 => (FailedElem, ctx)
      case (i, ctx)               => (Success(i), ctx)
    })

  val alwaysRecoveringFunc: ((Int, Int), (Try[Int], Int)) => Option[(Int, Int)] = {
    case (_, (Failure(_), i)) => Some(i -> i)
    case _                    => None
  }

  def failAllValuesFlow[Ctx]: FlowWithContext[Int, Ctx, Try[Int], Ctx, NotUsed] =
    FlowWithContext.fromTuples(Flow.fromFunction {
      case (_, j) => (FailedElem, j)
    })

  /** increments the context with every failure */
  def retryFailedValues[InData](in: (InData, Int), out: (Try[InData], Int)): Option[(InData, Int)] =
    out._1 match {
      case Failure(_) => Some((in._1, out._2 + 1))
      case _          => None
    }

  def incrementFailedValues[OutData](in: (Int, Int), out: (Try[OutData], Int)): Option[(Int, Int)] =
    out._1 match {
      case Failure(_) => Some((in._1 + 1, out._2 + 1))
      case _          => None
    }

  "RetryFlow.withBackoff" should {

    "send elements through" in {
      val sink =
        Source(List(13, 17, 19, 23, 27).map(_ -> 0))
          .via(RetryFlow.withBackoffAndContext(10.millis, 5.second, 0d, maxRetries = 3, failEvenValuesFlow[Int])(
            retryFailedValues))
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
          .via(RetryFlow.withBackoffAndContext(1.millis, 5.millis, 0d, maxRetries, failAllValuesFlow[Int])(
            retryFailedValues))
          .runWith(Sink.seq)
      sink.futureValue should contain inOrderElementsOf List(FailedElem -> maxRetries, FailedElem -> maxRetries)
    }

    "send elements through (with retrying)" in {
      val sink =
        Source(List(12, 13, 14).map(_ -> 0))
          .via(RetryFlow.withBackoffAndContext(1.millis, 5.millis, 0d, maxRetries = 3, failEvenValuesFlow[Int])(
            incrementFailedValues))
          .runWith(Sink.seq)
      sink.futureValue should contain inOrderElementsOf List(Success(13) -> 1, Success(13) -> 0, Success(15) -> 1)
    }

    "allow retrying a successful element" in {
      //#retry-success
      val flow: FlowWithContext[Int, Int, Try[Int], Int, NotUsed] = // ???
        //#retry-success
        FlowWithContext.fromTuples[Int, Int, Try[Int], Int, NotUsed](Flow.fromFunction {
          case (i, ctx) => Success(i) -> ctx
        })

      //#retry-success
      val retryFlow: FlowWithContext[Int, Int, Try[Int], Int, NotUsed] =
        RetryFlow.withBackoffAndContext(
          minBackoff = 10.millis,
          maxBackoff = 5.seconds,
          randomFactor = 0d,
          maxRetries = 3,
          flow)(decideRetry = {
          case ((_, _), (Success(i), ctx)) if i < 5 => Some((i + 1) -> (ctx + 1))
          case _                                    => None
        })
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

  }

  "Backing off" should {
    "have min backoff" in {
      val minBackoff = 200.millis
      val (source, sink) = TestSource
        .probe[(Int, Int)]
        .via(
          RetryFlow.withBackoffAndContext(minBackoff, 5.second, 0d, 3, failEvenValuesFlow[Int])(incrementFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1 -> 0)
      sink.expectNext(80.millis, Success(1) -> 0)

      source.sendNext(2 -> 0)
      sink.expectNoMessage(minBackoff)
      sink.expectNext(Success(3) -> 1)
    }

    "use min backoff for every try" in {
      val minBackoff = 50.millis
      val maxRetries = 3
      val (source, sink) = TestSource
        .probe[(Int, Int)]
        .via(RetryFlow.withBackoffAndContext(minBackoff, 5.seconds, 0d, maxRetries, failAllValuesFlow[Int])(
          retryFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(1)

      source.sendNext(10 -> 0)
      sink.expectNoMessage(minBackoff * maxRetries)
      sink.expectNext(FailedElem -> maxRetries)
    }

    "exponentially backoff between retries" in {
      val NumRetries = 7

      val nanoTimeOffset = System.nanoTime()
      case class State(retriedAt: List[Long])

      val flow = FlowWithContext.fromTuples[State, NotUsed, State, NotUsed, NotUsed](Flow.fromFunction {
        case (State(retriedAt), _) => State(System.nanoTime - nanoTimeOffset :: retriedAt) -> NotUsed
      })

      val (source, sink) = TestSource
        .probe[(State, NotUsed)]
        .via(RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, NumRetries, flow) {
          case (_, (s, _)) => Some(s -> NotUsed)
          case _           => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(1)

      source.sendNext(State(Nil) -> NotUsed)
      val (State(retriedAt), _) = sink.expectNext()

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
      val retryFlow: FlowWithContext[Int, Int, Try[Int], Int, NotUsed] =
        RetryFlow.withBackoffAndContext(
          minBackoff = 10.millis,
          maxBackoff = 5.seconds,
          randomFactor = 0d,
          maxRetries = 3,
          flow = failEvenValuesFlow[Int]) {
          case ((in, _), (Failure(_), ctx)) => Some((in + 1, ctx))
          case _                            => None
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
      sink.expectNext((Success(1), 1))

      source.sendNext(2)
      sink.expectNext((Success(3), 2))

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on start" in {
      val (killSwitch, sink) = TestSource
        .probe[(Int, Int)]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[(Int, Int)])(Keep.right)
        .via(
          RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, 3, failEvenValuesFlow[Int])(incrementFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort before start" in {
      val (killSwitch, sink) = TestSource
        .probe[(Int, Int)]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[(Int, Int)])(Keep.right)
        .via(
          RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, 3, failEvenValuesFlow[Int])(incrementFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(FailedElem.failed.get)

      sink.request(1)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow after start" in {
      val innerFlow =
        failEvenValuesFlow[Int]
          .via(Utils.delayCancellation(10.seconds))
          .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[(Int, Int)]
        .viaMat(RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, 3, innerFlow)(incrementFailedValues))(
          Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1 -> 0)
      sink.expectNext((Success(1), 0))

      source.sendNext(2 -> 0)
      sink.expectNext((Success(3), 1))

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow on start" in {
      val innerFlow =
        failEvenValuesFlow[Int]
          .via(Utils.delayCancellation(10.seconds))
          .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[(Int, Int)]
        .viaMat(RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, 3, innerFlow)(incrementFailedValues))(
          Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(FailedElem.failed.get)
      sink.expectError(FailedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow before start" in {
      val innerFlow =
        failEvenValuesFlow[Int]
          .via(Utils.delayCancellation(10.seconds))
          .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[(Int, Int)]
        .viaMat(RetryFlow.withBackoffAndContext(10.millis, 5.second, 0, 3, innerFlow)(incrementFailedValues))(
          Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(FailedElem.failed.get)

      sink.request(1)
      sink.expectError(FailedElem.failed.get)
    }

    val stuckForeverRetrying =
      RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, 3, failAllValuesFlow[Int])(alwaysRecoveringFunc)

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
          RetryFlow.withBackoffAndContext(
            10.millis,
            5.seconds,
            0d,
            3,
            failAllValuesFlow[Int]
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
        .probe[(Int, Int)]
        .via(
          RetryFlow.withBackoffAndContext(10.millis, 5.seconds, 0d, 3, failEvenValuesFlow[Int])(incrementFailedValues))
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1 -> 0)
      source.sendNext(3 -> 0)
      source.sendNext(2 -> 0)
      source.sendComplete()
      sink.expectNext(Success(1) -> 0)
      sink.expectNext(Success(3) -> 0)
      sink.expectNext(Success(3) -> 1)
      sink.expectComplete()
    }
  }

  "Coordinator" should {

    class ConstructBench[In, Ctx, Out](retryWith: ((In, Ctx), (Out, Ctx)) => Option[(In, Ctx)]) {

      def setup() = {
        val bidiFlow: BidiFlow[(In, Ctx), (In, Ctx), (Out, Ctx), (Out, Ctx), NotUsed] =
          BidiFlow.fromGraph(
            new RetryFlowCoordinator[In, Ctx, Out, Ctx](
              minBackoff = 10.millis,
              maxBackoff = 1.second,
              0d,
              3,
              retryWith))

        val throughDangerousFlow
            : FlowWithContext[In, Ctx, Out, Ctx, (TestSubscriber.Probe[(In, Ctx)], TestPublisher.Probe[(Out, Ctx)])] =
          FlowWithContext.fromTuples(
            Flow.fromSinkAndSourceMat(TestSink.probe[(In, Ctx)], TestSource.probe[(Out, Ctx)])(Keep.both))

        val inOutFlow: Flow[(In, Ctx), (Out, Ctx), (TestSubscriber.Probe[(In, Ctx)], TestPublisher.Probe[(Out, Ctx)])] =
          bidiFlow.joinMat(throughDangerousFlow)(Keep.right)

        val ((externalIn, (internalOut, internalIn)), externalOut) =
          TestSource.probe[(In, Ctx)].viaMat(inOutFlow)(Keep.both).toMat(TestSink.probe[(Out, Ctx)])(Keep.both).run()

        (externalIn, externalOut, internalIn, internalOut)
      }
      val (externalIn, externalOut, internalIn, internalOut) = setup()
    }

    type InData = String
    type UserCtx = Int
    type OutData = Try[String]

    "send successful elements accross" in new ConstructBench[InData, UserCtx, OutData]((_, _) => None) {
      // push element
      val elA = "A" -> 123
      externalIn.sendNext(elA)

      // let element go via retryable flow
      val elA2 = internalOut.requestNext()
      elA2._1 shouldBe elA._1
      elA2._2 shouldBe elA._2
      internalIn.sendNext(Success("result A") -> 123)

      // expect result
      externalOut.requestNext() shouldBe Success("result A") -> 123
    }

    "retry for a failure" in new ConstructBench[InData, UserCtx, OutData]((_, out) =>
      out._1 match {
        case Success(_) => None
        case Failure(_) => Some("A'" -> 2)
      }) {
      // demand on stream end
      externalOut.request(1)

      // push element
      val element1 = "A" -> 1
      externalIn.sendNext(element1)

      // let element go via retryable flow
      val try1 = internalOut.requestNext()
      try1._1 shouldBe element1._1
      try1._2 shouldBe element1._2
      internalIn.sendNext(Failure(new RuntimeException("boom")) -> 1)

      // let element go via retryable flow
      val try2 = internalOut.requestNext(3.seconds)
      try2._1 shouldBe "A'"
      try2._2 shouldBe 2
      internalIn.sendNext(Success("Ares") -> 1)

      // expect result
      externalOut.requestNext() shouldBe Success("Ares") -> 1
    }

    "allow to send two elements" in new ConstructBench[InData, UserCtx, OutData]((_, out) =>
      out._1 match {
        case Failure(_) => Some("A'" -> 2)
        case Success(_) => None
      }) {
      // demand on stream end
      externalOut.request(2)

      // push element
      val element1 = "A" -> 1
      externalIn.sendNext(element1)
      val element2 = "B" -> 2
      externalIn.sendNext(element2)

      // let element go via retryable flow
      val try1 = internalOut.requestNext()
      try1._1 shouldBe element1._1
      try1._2 shouldBe element1._2
      internalIn.sendNext(Failure(new RuntimeException("boom")) -> 1)

      // let element go via retryable flow
      val try2 = internalOut.requestNext()
      try2._1 shouldBe "A'"
      try2._2 shouldBe 2
      internalIn.sendNext(Success("Ares") -> 1)

      // let element2 go via retryable flow
      val try2_1 = internalOut.requestNext()
      try2_1._1 shouldBe element2._1
      try2_1._2 shouldBe element2._2
      internalIn.sendNext(Success("Bres") -> 1)

      // expect result
      externalOut.requestNext() shouldBe Success("Ares") -> 1
      externalOut.requestNext() shouldBe Success("Bres") -> 1
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

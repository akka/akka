/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.impl.RetryFlowCoordinator
import akka.stream.impl.RetryFlowCoordinator.{ RetryElement, RetryResult }
import akka.stream.{ BidiShape, FlowShape, Graph, KillSwitches }
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber, Utils }
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import org.scalatest.matchers.{ MatchResult, Matcher }

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class RetryFlowSpec extends StreamSpec() with CustomMatchers {

  val failedElem: Try[Int] = Failure(new Exception("cooked failure"))
  def flow[T]: Flow[(Int, T), (Try[Int], T), NotUsed] = Flow.fromFunction {
    case (i, j) if i % 2 == 0 => (failedElem, j)
    case (i, j)               => (Success(i + 1), j)
  }

  "RetryFlow.withBackoff" should {
    "skip elements that are retried with an empty collection" ignore {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (_, Failure(_), _) => Some(Nil)
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext((Success(2), 1))

      source.sendNext(2)
      sink.expectNoMessage()

      source.sendNext(3)
      sink.expectNext((Success(4), 3))

      source.sendNext(4)
      sink.expectNoMessage()

      source.sendComplete()
      sink.expectComplete()
    }

    "retry elements that fail while retrying" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (_, Failure(_), os) =>
            val s = os / 2
            if (s > 0) Some(List((s, s)))
            else Some(List((1, 1)))
          case _ => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext((Success(2), 1))

      source.sendNext(8)
      sink.expectNext((Success(2), 1))

      source.sendComplete()
      sink.expectComplete()
    }

    "tolerate killswitch abort after start" in {
      //#retry-failure
      val retryFlow = RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
        case (_, Failure(_), x) => Some(List((x + 1, x)))
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

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (_, Failure(_), x) => Some(List((x + 1, x)))
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort before start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .via(Utils.delayCancellation(10.seconds))
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (_, Failure(_), x) => Some(List((x + 1, x)))
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)

      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow after start" in {
      val innerFlow =
        flow[Int].via(Utils.delayCancellation(10.seconds)).viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, innerFlow) {
          case (_, Failure(_), x) => Some(List((x + 1, x)))
          case _                  => None
        })(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext((Success(2), 1))

      source.sendNext(2)
      sink.expectNext((Success(4), 2))

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow on start" in {
      val innerFlow =
        flow[Int].via(Utils.delayCancellation(10.seconds)).viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, innerFlow) {
          case (_, Failure(_), x) => Some(List((x + 1, x)))
          case _                  => None
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow before start" in {
      val innerFlow =
        flow[Int].via(Utils.delayCancellation(10.seconds)).viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, innerFlow) {
          case (_, Failure(_), x) => Some(List((x + 1, x)))
          case _                  => None
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)

      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    val alwaysFailingFlow = Flow.fromFunction[(Int, Int), (Try[Int], Int)] {
      case (_, j) => (failedElem, j)
    }

    val alwaysRecoveringFunc: (Int, Try[Int], Int) => Option[List[(Int, Int)]] = {
      case (_, Failure(_), i) => Some(List(i -> i))
      case _                  => None
    }

    val stuckForeverRetrying = RetryFlow.withBackoff(8, 10.millis, 5.second, 0, alwaysFailingFlow)(alwaysRecoveringFunc)

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

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow while on retry spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(
          RetryFlow.withBackoff(
            8,
            10.millis,
            5.second,
            0,
            alwaysFailingFlow
              .via(Utils.delayCancellation(10.seconds))
              .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right))(alwaysRecoveringFunc))(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
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

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "finish only after processing all elements in stream" ignore {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (_, Failure(_), x) => Some(List.fill(x)(1 -> 1))
          case _                  => None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNext((Success(2), 1))

      source.sendNext(3)
      sink.expectNext((Success(4), 3))

      source.sendNext(2)
      source.sendComplete()
      sink.expectNext((Success(2), 1))
      sink.expectNext((Success(2), 1))

      sink.expectComplete()
    }

    "exponentially backoff between retries" in {
      val NumRetries = 7

      val nanoTimeOffset = System.nanoTime()
      case class State(counter: Int, retriedAt: List[Long])

      val flow = Flow.fromFunction[(Int, State), (Try[Int], State)] {
        case (i, j) if i % NumRetries == 0 => (Success(i), j)
        case (_, State(counter, retriedAt)) =>
          (failedElem, State(counter + 1, System.nanoTime - nanoTimeOffset :: retriedAt))
      }

      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, State(i, Nil)))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow) {
          case (_, Failure(_), s @ State(counter, _)) => Some(List(counter -> s))
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

    "allow a retry for a successful element" in {
      val flow = Flow.fromFunction[(Int, Unit), (Try[Int], Unit)] {
        case (i, _) => (Success(i / 2), ())
      }

      //#retry-success
      val retryFlow = RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow) {
        case (_, Success(i), _) if i > 0 => Some(List((i, ())))
        case _                           => None
      }
      //#retry-success

      val (source, sink) = TestSource.probe[Int].map(i => (i, ())).via(retryFlow).toMat(TestSink.probe)(Keep.both).run()

      sink.request(4)

      source.sendNext(8)
      sink.expectNext((Success(4), ()))
      sink.expectNext((Success(2), ()))
      sink.expectNext((Success(1), ()))
      sink.expectNext((Success(0), ()))

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
        .via(RetryFlow.withBackoffAndContext(8, 10.millis, 5.second, 0, flow) {
          case (_, Failure(_), ctx) if ctx > 0 => Some(List((ctx / 2, ctx / 2)))
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

  "Coordinator" should {

    class ConstructBench[InData, UserState, OutData](
        retryWith: (InData, Try[OutData], UserState) => Option[(InData, UserState)]) {

      def setup() = {
        val retry: RetryFlowCoordinator[InData, UserState, OutData] =
          new RetryFlowCoordinator[InData, UserState, OutData](
            outBufferSize = 1,
            retryWith,
            minBackoff = 10.millis,
            maxBackoff = 1.second,
            0)

        val bidiFlow: BidiFlow[
          (InData, UserState),
          RetryElement[InData, UserState],
          RetryResult[InData, OutData, UserState],
          (Try[OutData], UserState),
          NotUsed] =
          BidiFlow.fromGraph(retry)

        val throughDangerousFlow: Flow[
          RetryElement[InData, UserState],
          RetryResult[InData, OutData, UserState],
          (
              TestSubscriber.Probe[RetryElement[InData, UserState]],
              TestPublisher.Probe[RetryResult[InData, OutData, UserState]])] =
          Flow.fromSinkAndSourceMat(
            TestSink.probe[RetryElement[InData, UserState]],
            TestSource.probe[RetryResult[InData, OutData, UserState]])(Keep.both)

        val inOutFlow: Flow[
          (InData, UserState),
          (Try[OutData], UserState),
          (
              TestSubscriber.Probe[RetryElement[InData, UserState]],
              TestPublisher.Probe[RetryResult[InData, OutData, UserState]])] =
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
      elA2.in shouldBe elA._1
      elA2.userState shouldBe elA._2
      internalIn.sendNext(new RetryResult(elA2, Success("result A"), 123))

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
      try1.in shouldBe element1._1
      try1.userState shouldBe element1._2
      internalIn.sendNext(new RetryResult(try1, Failure(new RuntimeException("boom")), 1))

      // let element go via retryable flow
      val try2 = internalOut.requestNext(3.seconds)
      try2.in shouldBe "A'"
      try2.userState shouldBe 2
      internalIn.sendNext(new RetryResult(try2, Success("Ares"), 1))

      // expect result
      externalOut.requestNext() shouldBe (Success("Ares"), 1)
    }

//    "retry two elements for a failure" in new ConstructBench({
//      val retryWith: (InData, Try[OutData], UserCtx) => Option[immutable.Iterable[(InData, UserCtx)]] = {
//        case (_, Success(t), state) => None
//        case (_, Failure(e), state) => Some(List(("A'", 2), ("A''", 3)))
//      }
//      retryWith
//    }) {
//
//      // demand on stream end
//      externalOut.request(1)
//
//      // push element
//      val element1 = ("A", 1)
//      externalIn.sendNext(element1)
//
//      // let element go via retryable flow
//      val try1 = internalOut.requestNext()
//      try1.in shouldBe element1._1
//      try1.userState shouldBe element1._2
//      internalIn.sendNext(new RetryResult(try1, Failure(new RuntimeException("boom")), 1))
//
//      // let element go via retryable flow
//      val try2 = internalOut.requestNext(3.seconds)
//      try2.in shouldBe "A'"
//      try2.userState shouldBe 2
//      internalIn.sendNext(new RetryResult(try2, Success("Ares"), 1))
//
//      // let element go via retryable flow
//      val try3 = internalOut.requestNext(3.seconds)
//      try3.in shouldBe "A''"
//      try3.userState shouldBe 3
//      internalIn.sendNext(new RetryResult(try3, Success("Ares'"), 1))
//
//      // expect result
//      externalOut.requestNext() shouldBe (Success("Ares"), 1)
//      externalOut.requestNext() shouldBe (Success("Ares'"), 1)
//    }

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
      try1.in shouldBe element1._1
      try1.userState shouldBe element1._2
      internalIn.sendNext(new RetryResult(try1, Failure(new RuntimeException("boom")), 1))

      // let element go via retryable flow
      val try2 = internalOut.requestNext()
      try2.in shouldBe "A'"
      try2.userState shouldBe 2
      internalIn.sendNext(new RetryResult(try2, Success("Ares"), 1))

      // let element2 go via retryable flow
      val try2_1 = internalOut.requestNext()
      try2_1.in shouldBe element2._1
      try2_1.userState shouldBe element2._2
      internalIn.sendNext(new RetryResult(try2_1, Success("Bres"), 1))

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

/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.{ActorMaterializer, KillSwitches}
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import org.scalatest.matchers.{MatchResult, Matcher}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class RetryFlowSpec extends StreamSpec() with CustomMatchers {

  implicit val mat: ActorMaterializer = ActorMaterializer()

  val failedElem: Try[Int] = Failure(new Exception("cooked failure"))
  def flow[T]: Flow[(Int, T), (Try[Int], T), NotUsed] = Flow.fromFunction {
    case (i, j) if i % 2 == 0 => (failedElem, j)
    case (i, j) => (Success(i + 1), j)
  }

  "RetryFlow.withBackoff" should {
    "skip elements that are retried with an empty collection" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (Failure(_), _) => Some(Nil)
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
          case (Failure(_), os) =>
            val s = os / 2
            if (s > 0) Some(List((s, s)))
            else Some(List((1, 1)))
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
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (Failure(_), x) => Some(List((x + 1, x)))
        })
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
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (Failure(_), x) => Some(List((x + 1, x)))
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
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (Failure(_), x) => Some(List((x + 1, x)))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)

      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow after start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, innerFlow) {
          case (Failure(_), x) => Some(List((x + 1, x)))
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
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, innerFlow) {
          case (Failure(_), x) => Some(List((x + 1, x)))
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch abort on the inner flow before start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, innerFlow) {
          case (Failure(_), x) => Some(List((x + 1, x)))
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

    val alwaysRecoveringFunc: PartialFunction[(Try[Int], Int), Option[List[(Int, Int)]]] = { case (Failure(_), i) => Some(List(i -> i)) }

    val stuckForeverRetrying = RetryFlow.withBackoff(8, 10.millis, 5.second, 0, alwaysFailingFlow)(alwaysRecoveringFunc)

    "tolerate killswitch abort before the RetryFlow while on retry spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
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
          RetryFlow.withBackoff(8, 10.millis, 5.second, 0,
            alwaysFailingFlow.viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right))(alwaysRecoveringFunc)
        )(Keep.both)
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
        .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)

      source.sendNext(1)
      sink.expectNoMessage()

      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "finish only after processing all elements in stream" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow[Int]) {
          case (Failure(_), x) => Some(List.fill(x)(1 -> 1))
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

      case class State(counter: Int, retriedAt: List[Long])

      val flow = Flow.fromFunction[(Int, State), (Try[Int], State)] {
        case (i, j) if i % NumRetries == 0 => (Success(i), j)
        case (_, State(counter, retriedAt)) => (failedElem, State(counter + 1, System.currentTimeMillis() :: retriedAt))
      }

      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, State(i, Nil)))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow) {
          case (Failure(_), s @ State(counter, _)) => Some(List(counter -> s))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(1)

      source.sendNext(1)
      val (result, State(_, retriedAt)) = sink.expectNext()

      result shouldBe Success(NumRetries)
      val timesBetweenRetries = retriedAt.sliding(2).collect {
        case before :: after :: Nil => before - after
      }.toIndexedSeq

      timesBetweenRetries.reverse should strictlyIncrease

      source.sendComplete()
      sink.expectComplete()
    }

    "allow a retry for a successful element" in {
      val flow = Flow.fromFunction[(Int, Unit), (Try[Int], Unit)] {
        case (i, _) => (Success(i / 2), ())
      }

      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, ()))
        .via(RetryFlow.withBackoff(8, 10.millis, 5.second, 0, flow) {
          case (Success(i), _) if i > 0 => Some(List((i, ())))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

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
        case i => Success(i)
      }

      val (source, sink) = TestSource
        .probe[Int]
        .asSourceWithContext(identity)
        .via(RetryFlow.withBackoffAndContext(8, 10.millis, 5.second, 0, flow) {
          case (Failure(_), ctx) if ctx > 0 => Some(List((ctx / 2, ctx / 2)))
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

}

trait CustomMatchers {

  class StrictlyIncreasesMatcher() extends Matcher[Seq[Long]] {

    def apply(left: Seq[Long]): MatchResult = {
      val result = left.sliding(2).map(pair => pair.head < pair.last).reduceOption(_ && _).getOrElse(false)

      MatchResult(
        result,
        s"""Collection $left elements are not increasing strictly""",
        s"""Collection $left elements are increasing strictly"""
      )
    }
  }

  def strictlyIncrease = new StrictlyIncreasesMatcher()
}

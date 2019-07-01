/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.{ActorMaterializer, KillSwitches}
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.{TestSink, TestSource}

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class RetryFlowSpec extends StreamSpec() {

  implicit val mat = ActorMaterializer()

  val failedElem: Try[Int] = Failure(new Exception("cooked failure"))
  def flow[T] = Flow.fromFunction[(Int, T), (Try[Int], T)] {
    case (i, j) if i % 2 == 0 => (failedElem, j)
    case (i, j) => (Success(i + 1), j)
  }

  "RetryFlow.withBackoff" should {
    "swallow failed elements that are retried with an empty Seq" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) { (_, _) =>
          Some(Nil)
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      sink.expectNoMessage()
      source.sendNext(3)
      assert(sink.expectNext()._1 === Success(4))
      source.sendNext(4)
      sink.expectNoMessage()
      source.sendComplete()
      sink.expectComplete()
    }

    "concat incremented ints and modulo 3 incremented ints from retries" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) { (_, os) =>
          val s = (os + 1) % 3
          if (os < 42) Some(List((os + 1, os + 1), (s, s)))
          else if (os == 42) Some(Nil)
          else None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(4))
      assert(sink.expectNext()._1 === Success(2))
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(44)
      assert(sink.expectNext()._1 === failedElem)
      source.sendNext(42)
      sink.expectNoMessage()
      source.sendComplete()
      sink.expectComplete()
    }

    "retry squares by division" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) {
          case (_, x) if x % 4 == 0 => Some(List((x / 2, x / 4)))
          case (_, x) => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some(List((sqrt, x)))
          }
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(4)
      assert(sink.expectNext()._1 === Success(2))
      sink.expectNoMessage(3.seconds)
      source.sendComplete()
      sink.expectComplete()
    }

    "tolerate killswitch terminations after start" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i * i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) {
          case (_, x) if x % 4 == 0 => Some(List((x / 2, x / 4)))
          case (_, x) => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some(List((sqrt, x)))
          }
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations on start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) { (_, x) =>
          Some(List((x, x + 1)))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations before start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) { (_, x) =>
          Some(List((x, x + 1)))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)
      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow after start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .viaMat(RetryFlow.withBackoff(100, 1.second, 1.second, 100, innerFlow) {
          case (_, x) if x % 4 == 0 => Some(List((x / 2, x / 4)))
          case (_, x) => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some(List((sqrt, x)))
          }
        })(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow on start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(100, 1.second, 1.second, 100, innerFlow) { (_, x) =>
          Some(List((x, x + 1)))
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow before start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(RetryFlow.withBackoff(100, 1.second, 1.second, 100, innerFlow) { (_, x) =>
          Some(List((x, x + 1)))
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

    val alwaysRecoveringFunc: (Try[Int], Int) => Option[List[(Int, Int)]] = (_, i) => Some(List(i -> i))

    val stuckForeverRetrying = RetryFlow.withBackoff(Int.MaxValue, 1.second, 1.second, Long.MaxValue, alwaysFailingFlow)(alwaysRecoveringFunc)

    "tolerate killswitch terminations before the flow while on fail spin" in {
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

    "tolerate killswitch terminations inside the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(
          RetryFlow.withBackoff(Int.MaxValue, 1.second, 1.second,
            Long.MaxValue,
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

    "tolerate killswitch terminations after the flow while on fail spin" in {
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
        .map(i => (i, i * i))
        .via(RetryFlow.withBackoff(100, 1.second, 1.second, 100, flow[Int]) {
          case (_, x) => Some(List.fill(x)(1 -> 1))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 == Success(2))

      source.sendNext(3)
      assert(sink.expectNext()._1 == Success(4))

      source.sendNext(2)
      source.sendComplete()
      assert(sink.expectNext()._1 == Success(2))
      assert(sink.expectNext()._1 == Success(2))
      assert(sink.expectNext()._1 == Success(2))
      assert(sink.expectNext()._1 == Success(2))

      sink.expectComplete()
    }
  }

}

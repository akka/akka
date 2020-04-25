/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.util.control.NoStackTrace

import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSink

class FlowMapErrorSpec extends StreamSpec("""
    akka.stream.materializer.initial-input-buffer-size = 1
    akka.stream.materializer.max-input-buffer-size = 1
  """) {

  val ex = new RuntimeException("ex") with NoStackTrace
  val boom = new Exception("BOOM!") with NoStackTrace

  "A MapError" must {
    "mapError when there is a handler" in assertAllStagesStopped {
      Source(1 to 4)
        .map { a =>
          if (a == 3) throw ex else a
        }
        .mapError { case _: Throwable => boom }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(1)
        .expectNext(2)
        .expectError(boom)
    }

    "fail the stream with exception thrown in handler (and log it)" in assertAllStagesStopped {
      Source(1 to 3)
        .map { a =>
          if (a == 2) throw ex else a
        }
        .mapError { case _: Exception => throw boom }
        .runWith(TestSink.probe[Int])
        .requestNext(1)
        .request(1)
        .expectError(boom)
    }

    "pass through the original exception if partial function does not handle it" in assertAllStagesStopped {
      Source(1 to 3)
        .map { a =>
          if (a == 2) throw ex else a
        }
        .mapError { case _: IndexOutOfBoundsException => boom }
        .runWith(TestSink.probe[Int])
        .requestNext(1)
        .request(1)
        .expectError(ex)
    }

    "not influence stream when there is no exceptions" in assertAllStagesStopped {
      Source(1 to 3)
        .map(identity)
        .mapError { case _: Throwable => boom }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNextN(1 to 3)
        .expectComplete()
    }

    "finish stream if it's empty" in assertAllStagesStopped {
      Source.empty
        .map(identity)
        .mapError { case _: Throwable => boom }
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectComplete()
    }
  }
}

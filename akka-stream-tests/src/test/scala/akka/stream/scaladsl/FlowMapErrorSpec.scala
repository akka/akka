/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }

import scala.util.control.NoStackTrace

class FlowMapErrorSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system).withInputBuffer(initialSize = 1, maxSize = 1)

  implicit val materializer = ActorMaterializer(settings)

  val ex = new RuntimeException("ex") with NoStackTrace
  val boom = new Exception("BOOM!") with NoStackTrace

  "A MapError" must {
    "mapError when there is a handler" in assertAllStagesStopped {
      Source(1 to 4).map { a ⇒ if (a == 3) throw ex else a }
        .mapError { case t: Throwable ⇒ boom }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(1)
        .expectNext(2)
        .expectError(boom)
    }

    "fail the stream with exception thrown in handler (and log it)" in assertAllStagesStopped {
      Source(1 to 3).map { a ⇒ if (a == 2) throw ex else a }
        .mapError { case t: Exception ⇒ throw boom }
        .runWith(TestSink.probe[Int])
        .requestNext(1)
        .request(1)
        .expectError(boom)
    }

    "pass through the original exception if partial function does not handle it" in assertAllStagesStopped {
      Source(1 to 3).map { a ⇒ if (a == 2) throw ex else a }
        .mapError { case t: IndexOutOfBoundsException ⇒ boom }
        .runWith(TestSink.probe[Int])
        .requestNext(1)
        .request(1)
        .expectError(ex)
    }

    "not influence stream when there is no exceptions" in assertAllStagesStopped {
      Source(1 to 3).map(identity)
        .mapError { case t: Throwable ⇒ boom }
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNextN(1 to 3)
        .expectComplete()
    }

    "finish stream if it's empty" in assertAllStagesStopped {
      Source.empty.map(identity)
        .mapError { case t: Throwable ⇒ boom }
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectComplete()
    }
  }
}

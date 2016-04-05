/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.util

import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

class StreamUtilsSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "captureTermination" should {
    "signal completion" when {
      "upstream terminates" in {
        val (newSource, whenCompleted) = StreamUtils.captureTermination(Source(List(1, 2, 3)))

        newSource.runWith(Sink.ignore)

        Await.result(whenCompleted, 3.seconds) shouldBe ()
      }

      "upstream fails" in {
        val ex = new RuntimeException("ex")
        val (newSource, whenCompleted) = StreamUtils.captureTermination(Source.failed[Int](ex))
        intercept[RuntimeException] {
          Await.result(newSource.runWith(Sink.head), 3.second)
        } should be theSameInstanceAs ex

        Await.ready(whenCompleted, 3.seconds).value shouldBe Some(Failure(ex))
      }

      "downstream cancels" in {
        val (newSource, whenCompleted) = StreamUtils.captureTermination(Source(List(1, 2, 3)))

        newSource.runWith(Sink.head)

        Await.result(whenCompleted, 3.seconds) shouldBe ()
      }
    }
  }

}

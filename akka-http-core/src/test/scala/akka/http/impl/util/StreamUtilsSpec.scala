/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.util

import akka.stream.{ ActorMaterializer, Attributes }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

class StreamUtilsSpec extends AkkaSpec with ScalaFutures {
  implicit val materializer = ActorMaterializer()

  "captureTermination" should {
    "signal completion" when {
      "upstream terminates" in {
        val (newSource, whenCompleted) = StreamUtils.captureTermination(Source(List(1, 2, 3)))

        newSource.runWith(Sink.ignore)

        Await.result(whenCompleted, 3.seconds.dilated) shouldBe (())
      }

      "upstream fails" in {
        val ex = new RuntimeException("ex")
        val (newSource, whenCompleted) = StreamUtils.captureTermination(Source.failed[Int](ex))
        intercept[RuntimeException] {
          Await.result(newSource.runWith(Sink.head), 3.second.dilated)
        } should be theSameInstanceAs ex

        Await.ready(whenCompleted, 3.seconds.dilated).value shouldBe Some(Failure(ex))
      }

      "downstream cancels" in {
        val (newSource, whenCompleted) = StreamUtils.captureTermination(Source(List(1, 2, 3)))

        newSource.runWith(Sink.head)

        Await.result(whenCompleted, 3.seconds.dilated) shouldBe (())
      }
    }
  }

  "exposeAttributes" should {
    "expose attrs" in {
      val element = "hello"
      val nameAttr = Attributes.name("Amazing")

      val res =
        Source.single(element)
          .via(StreamUtils.statefulAttrsMap(attrs ⇒ el ⇒ attrs → el))
          .addAttributes(nameAttr)
          .runWith(Sink.head)

      val (attrs, `element`) = res.futureValue
      attrs.attributeList should contain(nameAttr.attributeList.head)
    }
  }

}

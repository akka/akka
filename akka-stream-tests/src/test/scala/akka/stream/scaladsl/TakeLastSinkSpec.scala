/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }

class TakeLastSinkSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sink.takeLast" must {
    "return the last elements" in {
      val input = 1 to 6
      val future: Future[immutable.Seq[Int]] = Source(input).runWith(Sink.takeLast(3))
      val result: immutable.Seq[Int] = Await.result(future, remainingOrDefault)
      result should be(Seq(4, 5, 6))
    }

    "return the number of elements taken when the stream completes" in {
      val input = 1 to 4
      val future: Future[immutable.Seq[Int]] = Source(input).runWith(Sink.takeLast(5))
      val result: immutable.Seq[Int] = Await.result(future, remainingOrDefault)
      result should be(Seq(1, 2, 3, 4))
    }

  }
}

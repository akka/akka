/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.stream.{ AbruptTerminationException, ActorMaterializer, ActorMaterializerSettings }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }

class TakeLastSinkSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sink.takeLast" must {
    "return the last 3 elements" in {
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

    "fail future when stream abruptly terminated" in {
      val mat = ActorMaterializer()
      val probe = TestPublisher.probe()
      val future: Future[immutable.Seq[Int]] =
        Source.fromPublisher(probe).runWith(Sink.takeLast(2))(mat)
      mat.shutdown()
      future.failed.futureValue shouldBe an[AbruptTerminationException]
    }

    "yield empty seq for empty stream" in {
      val future: Future[immutable.Seq[Int]] = Source.empty[Int].runWith(Sink.takeLast(3))
      val result: immutable.Seq[Int] = Await.result(future, remainingOrDefault)
      result should be(Seq.empty)
    }

    "return the last element" in {
      val input = 1 to 4
      val future: Future[immutable.Seq[Int]] = Source(input).runWith(Sink.takeLast(1))
      val result: immutable.Seq[Int] = Await.result(future, remainingOrDefault)
      result should be(Seq(4))
    }
  }
}

/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.stream.{ AbruptTerminationException, ActorMaterializer, ActorMaterializerSettings }

import scala.collection.immutable
import scala.concurrent.{ Await, Future }

class SeqSinkSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorMaterializer(settings)

  "Sink.toSeq" must {
    "return a Seq[T] from a Source" in {
      val input = (1 to 6)
      val future: Future[immutable.Seq[Int]] = Source(input).runWith(Sink.seq)
      val result: immutable.Seq[Int] = Await.result(future, remainingOrDefault)
      result should be(input.toSeq)
    }

    "return an empty Seq[T] from an empty Source" in {
      val input: immutable.Seq[Int] = Nil
      val future: Future[immutable.Seq[Int]] = Source.fromIterator(() ⇒ input.iterator).runWith(Sink.seq)
      val result: immutable.Seq[Int] = Await.result(future, remainingOrDefault)
      result should be(input)
    }

    "fail the future on abrupt termination" in {
      val mat = ActorMaterializer()
      val probe = TestPublisher.probe()
      val future: Future[immutable.Seq[Int]] =
        Source.fromPublisher(probe).runWith(Sink.seq)(mat)
      mat.shutdown()
      future.failed.futureValue shouldBe an[AbruptTerminationException]
    }
  }
}

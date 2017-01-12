/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.StreamSpec
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import scala.collection.immutable
import scala.concurrent.{ Future, Await }

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
  }
}

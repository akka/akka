/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.testkit.StreamSpec

case class Message(data: String, offset: Long)

class SourceWithContextSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  "A SourceWithContext" must {

    "get created from Source.asSourceWithContext" in {
      val msg = Message("a", 1L)
      Source(Vector(msg))
        .asSourceWithContext(_.offset)
        .toMat(TestSink.probe[(Message, Long)])(Keep.right)
        .run
        .request(1)
        .expectNext((msg, 1L))
        .expectComplete()
    }

    "get created from a source of tuple2" in {
      val msg = Message("a", 1L)
      SourceWithContext
        .fromTuples(Source(Vector((msg, msg.offset))))
        .asSource
        .runWith(TestSink.probe[(Message, Long)])
        .request(1)
        .expectNext((msg, 1L))
        .expectComplete()
    }

    "be able to get turned back into a normal Source" in {
      val msg = Message("a", 1L)
      Source(Vector(msg))
        .asSourceWithContext(_.offset)
        .map(_.data)
        .asSource
        .map { case (e, _) => e }
        .runWith(TestSink.probe[String])
        .request(1)
        .expectNext("a")
        .expectComplete()
    }

    "pass through contexts using map and filter" in {
      Source(Vector(Message("A", 1L), Message("B", 2L), Message("D", 3L), Message("C", 4L)))
        .asSourceWithContext(_.offset)
        .map(_.data.toLowerCase)
        .filter(_ != "b")
        .filterNot(_ == "d")
        .toMat(TestSink.probe[(String, Long)])(Keep.right)
        .run
        .request(2)
        .expectNext(("a", 1L))
        .expectNext(("c", 4L))
        .expectComplete()
    }

    "pass through contexts via a FlowWithContext" in {

      def flowWithContext[T] = FlowWithContext[T, Long]

      Source(Vector(Message("a", 1L)))
        .asSourceWithContext(_.offset)
        .map(_.data)
        .via(flowWithContext.map(s => s + "b"))
        .runWith(TestSink.probe[(String, Long)])
        .request(1)
        .expectNext(("ab", 1L))
        .expectComplete()
    }

    "pass through contexts via mapConcat" in {
      Source(Vector(Message("a", 1L)))
        .asSourceWithContext(_.offset)
        .map(_.data)
        .mapConcat { str =>
          List(1, 2, 3).map(i => s"$str-$i")
        }
        .runWith(TestSink.probe[(String, Long)])
        .request(3)
        .expectNext(("a-1", 1L), ("a-2", 1L), ("a-3", 1L))
        .expectComplete()
    }

    "pass through a sequence of contexts per element via grouped" in {
      Source(Vector(Message("a", 1L)))
        .asSourceWithContext(_.offset)
        .map(_.data)
        .mapConcat { str =>
          List(1, 2, 3, 4).map(i => s"$str-$i")
        }
        .grouped(2)
        .toMat(TestSink.probe[(Seq[String], Seq[Long])])(Keep.right)
        .run
        .request(2)
        .expectNext((Seq("a-1", "a-2"), Seq(1L, 1L)), (Seq("a-3", "a-4"), Seq(1L, 1L)))
        .expectComplete()
    }
  }
}

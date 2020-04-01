/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.ContextMapStrategy
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.TestSink

import scala.util.control.NoStackTrace

case class Message(data: String, offset: Long)

class SourceWithContextSpec extends StreamSpec {

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

    final case class OffsetContext(offset: Long, position: String = "only")

    val iterateStrategy = ContextMapStrategy.iterate(
      fn = (_: String, inCtx: OffsetContext, _: String, index: Long, hasNext: Boolean) => {
        if (index == 0 && hasNext) OffsetContext(inCtx.offset, "first")
        else if (!hasNext) OffsetContext(inCtx.offset, "last")
        else OffsetContext(inCtx.offset, "within")
      }
    )

    val emptyStrategy = ContextMapStrategy.iterateOrEmpty(
      fn = (_: String, inCtx: OffsetContext, _: String, _: Long, _: Boolean) => inCtx,
      emptyFn = (_: String, inCtx: OffsetContext) => ("empty",  OffsetContext(inCtx.offset, "empty"))
    )

    "use iterate strategy on contexts via mapConcat" in {
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .mapConcat(
          { str =>
            List(1, 2, 3).map(i => s"$str-$i")
          },
          iterateStrategy
        )
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(3)
        .expectNext(("a-1", OffsetContext(1L, "first")), ("a-2", OffsetContext(1L, "within")), ("a-3", OffsetContext(1L, "last")))
        .expectComplete()
    }

    "use iterateOrEmpty strategy on contexts via mapConcat" in {
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .mapConcat(
          f = _ => Nil,
          emptyStrategy
        )
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(1)
        .expectNext(("empty", OffsetContext(1L, "empty")))
        .expectComplete()
    }

    "use iterate strategy on contexts via flatMapConcat" in {
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .flatMapConcat(
          { str =>
            Source(1 to 3).map(i => s"$str-$i")
          },
          iterateStrategy
        )
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(3)
        .expectNext(("a-1", OffsetContext(1L, "first")), ("a-2", OffsetContext(1L, "within")), ("a-3", OffsetContext(1L, "last")))
        .expectComplete()
    }

    "use iterateOrEmpty strategy on contexts via flatMapConcat" in {
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .flatMapConcat(
          f = _ => Source.empty[String],
          emptyStrategy
        )
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(1)
        .expectNext(("empty", OffsetContext(1L, "empty")))
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

    "be able to change materialized value via mapMaterializedValue" in {
      val materializedValue = "MatedValue"
      Source
        .empty[Message]
        .asSourceWithContext(_.offset)
        .mapMaterializedValue(_ => materializedValue)
        .to(Sink.ignore)
        .run() shouldBe materializedValue
    }

    "be able to map error via mapError" in {
      val ex = new RuntimeException("ex") with NoStackTrace
      val boom = new Exception("BOOM!") with NoStackTrace

      Source(1L to 4L)
        .map { offset =>
          Message("a", offset)
        }
        .map {
          case m @ Message(_, offset) => if (offset == 3) throw ex else m
        }
        .asSourceWithContext(_.offset)
        .mapError { case _: Throwable => boom }
        .runWith(TestSink.probe[(Message, Long)])
        .request(3)
        .expectNext((Message("a", 1L), 1L))
        .expectNext((Message("a", 2L), 2L))
        .expectError(boom)
    }
  }
}

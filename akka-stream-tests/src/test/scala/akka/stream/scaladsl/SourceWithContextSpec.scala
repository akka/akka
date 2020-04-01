/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.util.control.NoStackTrace
import akka.NotUsed
import akka.stream.ContextMapStrategy
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.Future

case class Message(data: String, offset: Long)

class SourceWithContextSpec extends StreamSpec {

  "A SourceWithContext" must {

    // This could perhaps be a default strategy, for adding context as metadata that is OK to be reordered, duplicated or lost?
    // Perhaps dangerous though.
    def allowAllStrategy[Ctx, In, Out] = new ContextMapStrategy[Ctx] with ContextMapStrategy.Reordering[Ctx] with ContextMapStrategy.Iteration[Ctx, In, Out] {
      override def next(inputElement: In, in: Ctx, outputElement: Out, index: Long, hasNext: Boolean): Ctx = in
    }

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
        .filter(_ != "b", allowAllStrategy)
        // TODO also add strategy to filterNot
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
        .mapConcat({ str =>
          List(1, 2, 3).map(i => s"$str-$i")
        }, allowAllStrategy[Long, String, String])
        .runWith(TestSink.probe[(String, Long)])
        .request(3)
        .expectNext(("a-1", 1L), ("a-2", 1L), ("a-3", 1L))
        .expectComplete()
    }

    /* (could be Alpakka Kafka's CommittableOffset) */
    final case class OffsetContext(offset: Long, position: String = "only")

//    val iterateStrategy = ContextMapStrategy.iterate(
//      fn = (_: String, inCtx: OffsetContext, _: String, index: Long, hasNext: Boolean) => {
//        if (index == 0 && hasNext) OffsetContext(inCtx.offset, "first")
//        else if (!hasNext) OffsetContext(inCtx.offset, "last")
//        else OffsetContext(inCtx.offset, "within")
//      }
//    )
//
//    val emptyStrategy = ContextMapStrategy.iterateOrEmpty(
//      fn = (_: String, inCtx: OffsetContext, _: String, _: Long, _: Boolean) => inCtx,
//      emptyFn = (_: String, inCtx: OffsetContext) => ("empty",  OffsetContext(inCtx.offset, "empty"))
//    )

//    "use iterate strategy on contexts via mapConcat" in {

    /*
     * (This could be provided by alpakka-kafka. It would be coupled with a Sink that would commit any Some(offset) it sees.)
     *
     * A simple strategy that allows iteration and filtering (but not reordering or substreaming).
     */
    object SimpleKafkaContextMapStrategy extends ContextMapStrategy[OffsetContext]
      with ContextMapStrategy.Iteration[OffsetContext, String, String] {

      override def next(inputElement: String, in: OffsetContext, outputElement: String, index: Long, hasNext: Boolean): OffsetContext = {
        if (index == 0 && hasNext) OffsetContext(in.offset, "first")
        else if (!hasNext) OffsetContext(in.offset, "last")
        else OffsetContext(in.offset, "within")
      }

      override def empty(inputElement: String, in: OffsetContext): Option[(String, OffsetContext)] = Some(("empty", in.copy(position = "empty")))
    }


    /*
     * (This could be provided by alpakka-kafka. It would be coupled with a Sink that keeps track of which offsets are ready to be committed,
     * and which are still being waited on.
     *
     * A strategy that allows reordering, but not iteration or filtering (but not reordering or substreaming).
     */
    object ReorderingKafkaContextMapStrategy extends ContextMapStrategy[OffsetContext] with ContextMapStrategy.Reordering[OffsetContext] {

    }

    /**
     * (This could be some Alpakka function, creating flows that are usable with any strategy as long as it allows iteration)
     */
    def sendToFooSelective[Ctx](contextMapStrategy: ContextMapStrategy.Filtering[Ctx]): FlowWithContext[String, Ctx, String, Ctx, NotUsed] =
      FlowWithContext[String, Ctx]
        .filter(!_.contains("2"), contextMapStrategy)

    "use a mapConcat strategy that allows iteration in a flow that uses iteration" in {
      val contextMapping = SimpleKafkaContextMapStrategy
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .mapConcat(
          { str =>
            List(1, 2, 3).map(i => s"$str-$i")
          },
          contextMapping
        )
        .via(sendToFooSelective(contextMapping))
        .runWith(TestSink.probe[(String,OffsetContext)])
        .request(3)
        .expectNext(("a-1", OffsetContext(1L, "first")), ("a-3", OffsetContext(1L, "last")))
        .expectComplete()
    }

    "use a mapConcat strategy that allows iteration in a flow that emits an empty iterator" in {
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .mapConcat(
          f = _ => Nil,
          SimpleKafkaContextMapStrategy
        )
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(1)
        .expectNext(("empty", OffsetContext(1L, "empty")))
        .expectComplete()
    }

    "use a flatMapConcat strategy that allows iteration in a flow that uses iteration" in {
      val contextMapping = SimpleKafkaContextMapStrategy
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .flatMapConcat(
          { str =>
            Source(1 to 3).map(i => s"$str-$i")
          },
          contextMapping
        )
        .via(sendToFooSelective(contextMapping))
        .runWith(TestSink.probe[(String,OffsetContext)])
        .request(3)
        .expectNext(("a-1", OffsetContext(1L, "first")), ("a-3", OffsetContext(1L, "last")))
        .expectComplete()
    }

    "use a flatMapConcat strategy that allows iteration in a flow that emits an empty iterator" in {
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .flatMapConcat(
          f = _ => Source.empty,
          SimpleKafkaContextMapStrategy
        )
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(1)
        .expectNext(("empty", OffsetContext(1L, "empty")))
        .expectComplete()
    }

    "use a strategy that allows reordering in a flow that uses reordering" in {
      /**
       * (This could be some Alpakka function, creating flows that are usable with any strategy as long as it allows reordering)
       */
      def sendToFooFast[Ctx](strategy: ContextMapStrategy.Reordering[Ctx]): FlowWithContext[String, Ctx, String, Ctx, NotUsed] =
        FlowWithContext[String, Ctx]
          .mapAsyncUnordered(100, strategy)(msg => Future.successful(s"[$msg] successfully sent"))

      val contextMapping = ReorderingKafkaContextMapStrategy
      Source(Message("a", 1L) :: Nil)
        .asSourceWithContext(msg => OffsetContext(msg.offset))
        .map(_.data)
        .mapAsyncUnordered(
          parallelism = 100,
          contextMapping)(
          data => Future.successful(s"$data to send"),
        )
        .via(sendToFooFast(contextMapping))
        .runWith(TestSink.probe[(String, OffsetContext)])
        .request(1)
        .expectNext(("[a to send] successfully sent", OffsetContext(1L)))
        .expectComplete()
    }

    "pass through a sequence of contexts per element via grouped" in {
      Source(Vector(Message("a", 1L)))
        .asSourceWithContext(_.offset)
        .map(_.data)
        .mapConcat({ str =>
          List(1, 2, 3, 4).map(i => s"$str-$i")
        }, allowAllStrategy[Long, String, String])
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

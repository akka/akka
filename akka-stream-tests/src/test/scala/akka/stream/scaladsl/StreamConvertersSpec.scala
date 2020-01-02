/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util
import java.util.function.BiConsumer
import java.util.function.BinaryOperator
import java.util.function.Supplier
import java.util.function.ToIntFunction
import java.util.stream.Collector.Characteristics
import java.util.stream.BaseStream
import java.util.stream.Collector
import java.util.stream.Collectors

import akka.stream.testkit.StreamSpec
import akka.stream.testkit.Utils.TE
import akka.testkit.DefaultTimeout
import org.scalatest.time.Millis
import org.scalatest.time.Span

import scala.concurrent.Await
import scala.concurrent.duration._

class StreamConvertersSpec extends StreamSpec with DefaultTimeout {

  implicit val config = PatienceConfig(timeout = Span(timeout.duration.toMillis, Millis))

  "Java Stream source" must {
    import java.util.stream.IntStream
    import java.util.stream.Stream

    import scala.compat.java8.FunctionConverters._

    def javaStreamInts =
      IntStream.iterate(1, { i: Int =>
        i + 1
      }.asJava)

    "work with Java collections" in {
      val list = new java.util.LinkedList[Integer]()
      list.add(0)
      list.add(1)
      list.add(2)

      StreamConverters.fromJavaStream(() => list.stream()).map(_.intValue).runWith(Sink.seq).futureValue should ===(
        List(0, 1, 2))
    }

    "work with primitive streams" in {
      StreamConverters
        .fromJavaStream(() => IntStream.rangeClosed(1, 10))
        .map(_.intValue)
        .runWith(Sink.seq)
        .futureValue should ===(1 to 10)
    }

    "work with an empty stream" in {
      StreamConverters.fromJavaStream(() => Stream.empty[Int]()).runWith(Sink.seq).futureValue should ===(Nil)
    }

    "work with an infinite stream" in {
      StreamConverters.fromJavaStream(() => javaStreamInts).take(1000).runFold(0)(_ + _).futureValue should ===(500500)
    }

    "work with a filtered stream" in {
      StreamConverters
        .fromJavaStream(() =>
          javaStreamInts.filter({ i: Int =>
            i % 2 == 0
          }.asJava))
        .take(1000)
        .runFold(0)(_ + _)
        .futureValue should ===(1001000)
    }

    "properly report errors during iteration" in {
      import akka.stream.testkit.Utils.TE
      // Filtering is lazy on Java Stream

      val failyFilter: Int => Boolean = _ => throw TE("failing filter")

      a[TE] must be thrownBy {
        Await.result(
          StreamConverters.fromJavaStream(() => javaStreamInts.filter(failyFilter.asJava)).runWith(Sink.ignore),
          3.seconds)
      }
    }

    "close the underlying stream when completed" in {
      @volatile var closed = false

      final class EmptyStream[A] extends BaseStream[A, EmptyStream[A]] {
        override def unordered(): EmptyStream[A] = this
        override def sequential(): EmptyStream[A] = this
        override def parallel(): EmptyStream[A] = this
        override def isParallel: Boolean = false

        override def spliterator(): util.Spliterator[A] = ???
        override def onClose(closeHandler: Runnable): EmptyStream[A] = ???

        override def iterator(): util.Iterator[A] = new util.Iterator[A] {
          override def next(): A = ???
          override def hasNext: Boolean = false
        }

        override def close(): Unit = closed = true
      }

      Await.ready(StreamConverters.fromJavaStream(() => new EmptyStream[Unit]).runWith(Sink.ignore), 3.seconds)

      closed should ===(true)
    }

    "close the underlying stream when failed" in {
      @volatile var closed = false

      final class FailingStream[A] extends BaseStream[A, FailingStream[A]] {
        override def unordered(): FailingStream[A] = this
        override def sequential(): FailingStream[A] = this
        override def parallel(): FailingStream[A] = this
        override def isParallel: Boolean = false

        override def spliterator(): util.Spliterator[A] = ???
        override def onClose(closeHandler: Runnable): FailingStream[A] = ???

        override def iterator(): util.Iterator[A] = new util.Iterator[A] {
          override def next(): A = throw new TE("ouch")
          override def hasNext: Boolean = true
        }

        override def close(): Unit = closed = true
      }

      Await.ready(StreamConverters.fromJavaStream(() => new FailingStream[Unit]).runWith(Sink.ignore), 3.seconds)

      closed should ===(true)
    }

    // Repeater for #24304
    "not throw stack overflow with a large source" in {
      Source
        .repeat(Integer.valueOf(1))
        .take(100000)
        .runWith(StreamConverters.javaCollector[Integer, Integer] { () =>
          Collectors.summingInt(new ToIntFunction[Integer] {
            def applyAsInt(value: Integer): Int = value
          })
        })
        .futureValue
    }

    "not share collector across materializations" in {
      val stream = Source
        .repeat(1)
        .take(10)
        .toMat(StreamConverters.javaCollector[Int, Integer] { () =>
          Collectors.summingInt(new ToIntFunction[Int] {
            def applyAsInt(value: Int): Int = value
          })
        })(Keep.right)
      stream.run().futureValue should ===(Integer.valueOf(10))
      stream.run().futureValue should ===(Integer.valueOf(10))
    }

  }

  "Java collector Sink" must {

    class TestCollector(
        _supplier: () => Supplier[Array[Int]],
        _accumulator: () => BiConsumer[Array[Int], Int],
        _combiner: () => BinaryOperator[Array[Int]],
        _finisher: () => java.util.function.Function[Array[Int], Int])
        extends Collector[Int, Array[Int], Int] {
      override def supplier(): Supplier[Array[Int]] = _supplier()
      override def combiner(): BinaryOperator[Array[Int]] = _combiner()
      override def finisher(): java.util.function.Function[Array[Int], Int] = _finisher()
      override def accumulator(): BiConsumer[Array[Int], Int] = _accumulator()
      override def characteristics(): util.Set[Characteristics] = util.Collections.emptySet()
    }

    val intIdentity: ToIntFunction[Int] = new ToIntFunction[Int] {
      override def applyAsInt(value: Int): Int = value
    }

    def supplier(): Supplier[Array[Int]] = new Supplier[Array[Int]] {
      override def get(): Array[Int] = new Array(1)
    }
    def accumulator(): BiConsumer[Array[Int], Int] = new BiConsumer[Array[Int], Int] {
      override def accept(a: Array[Int], b: Int): Unit = a(0) = intIdentity.applyAsInt(b)
    }

    def combiner(): BinaryOperator[Array[Int]] = new BinaryOperator[Array[Int]] {
      override def apply(a: Array[Int], b: Array[Int]): Array[Int] = {
        a(0) += b(0); a
      }
    }
    def finisher(): java.util.function.Function[Array[Int], Int] = new java.util.function.Function[Array[Int], Int] {
      override def apply(a: Array[Int]): Int = a(0)
    }

    "work in the happy case" in {
      Source(1 to 100)
        .map(_.toString)
        .runWith(StreamConverters.javaCollector(() => Collectors.joining(", ")))
        .futureValue should ===((1 to 100).mkString(", "))
    }

    "work with an empty source" in {
      Source
        .empty[Int]
        .map(_.toString)
        .runWith(StreamConverters.javaCollector(() => Collectors.joining(", ")))
        .futureValue should ===("")
    }

    "work parallelly in the happy case" in {
      Source(1 to 100)
        .runWith(StreamConverters.javaCollectorParallelUnordered(4)(() => Collectors.summingInt[Int](intIdentity)))
        .futureValue
        .toInt should ===(5050)
    }

    "work parallelly with an empty source" in {
      Source
        .empty[Int]
        .map(_.toString)
        .runWith(StreamConverters.javaCollectorParallelUnordered(4)(() => Collectors.joining(", ")))
        .futureValue should ===("")
    }

    "be reusable" in {
      val sink = StreamConverters.javaCollector[Int, Integer](() => Collectors.summingInt[Int](intIdentity))
      Source(1 to 4).runWith(sink).futureValue.toInt should ===(10)

      // Collector has state so it preserves all previous elements that went though
      Source(4 to 6).runWith(sink).futureValue.toInt should ===(15)
    }

    "be reusable with parallel version" in {
      val sink = StreamConverters.javaCollectorParallelUnordered(4)(() => Collectors.summingInt[Int](intIdentity))

      Source(1 to 4).runWith(sink).futureValue.toInt should ===(10)
      Source(4 to 6).runWith(sink).futureValue.toInt should ===(15)
    }

    "fail if getting the supplier fails" in {
      def failedSupplier(): Supplier[Array[Int]] = throw TE("")
      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(failedSupplier _, accumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if the supplier fails" in {
      def failedSupplier(): Supplier[Array[Int]] = new Supplier[Array[Int]] {
        override def get(): Array[Int] = throw TE("")
      }
      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(failedSupplier _, accumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if getting the accumulator fails" in {
      def failedAccumulator(): BiConsumer[Array[Int], Int] = throw TE("")

      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, failedAccumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if the accumulator fails" in {
      def failedAccumulator(): BiConsumer[Array[Int], Int] = new BiConsumer[Array[Int], Int] {
        override def accept(a: Array[Int], b: Int): Unit = throw TE("")
      }

      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, failedAccumulator _, combiner _, finisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if getting the finisher fails" in {
      def failedFinisher(): java.util.function.Function[Array[Int], Int] = throw TE("")

      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, accumulator _, combiner _, failedFinisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

    "fail if the finisher fails" in {
      def failedFinisher(): java.util.function.Function[Array[Int], Int] =
        new java.util.function.Function[Array[Int], Int] {
          override def apply(a: Array[Int]): Int = throw TE("")
        }
      val future = Source(1 to 100).runWith(StreamConverters.javaCollector(() =>
        new TestCollector(supplier _, accumulator _, combiner _, failedFinisher _)))
      a[TE] shouldBe thrownBy {
        Await.result(future, 300.millis)
      }
    }

  }

}

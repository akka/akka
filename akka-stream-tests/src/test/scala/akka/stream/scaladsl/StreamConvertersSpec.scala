/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util
import java.util.stream.BaseStream

import akka.stream.ActorMaterializer
import akka.stream.testkit.StreamSpec
import akka.testkit.DefaultTimeout
import org.scalatest.time.{ Millis, Span }

import scala.concurrent.Await
import scala.concurrent.duration._

class StreamConvertersSpec extends StreamSpec with DefaultTimeout {

  implicit val materializer = ActorMaterializer()
  implicit val config = PatienceConfig(timeout = Span(timeout.duration.toMillis, Millis))

  "Java Stream source" must {
    import scala.compat.java8.FunctionConverters._
    import java.util.stream.{ IntStream, Stream }

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
          override def next(): A = throw new RuntimeException("ouch")
          override def hasNext: Boolean = true
        }

        override def close(): Unit = closed = true
      }

      Await.ready(StreamConverters.fromJavaStream(() => new FailingStream[Unit]).runWith(Sink.ignore), 3.seconds)

      closed should ===(true)
    }
  }

}

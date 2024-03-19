/*
 * Copyright (C) 2014-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.stream.OverflowStrategy
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSink

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.control.NoStackTrace

class FlowFlatMapConcatSpec extends StreamSpec("""
    akka.stream.materializer.initial-input-buffer-size = 2
  """) with ScriptedTest {
  val toSeq = Flow[Int].grouped(1000).toMat(Sink.head)(Keep.right)

  class BoomException extends RuntimeException("BOOM~~") with NoStackTrace
  "A flatMapConcat" must {

    "work with value presented sources" in {
      Source(
        List(
          Source.empty[Int],
          Source.single(1),
          Source.empty[Int],
          Source(List(2, 3, 4)),
          Source.future(Future.successful(5)),
          Source.lazyFuture(() => Future.successful(6))))
        .flatMapConcat(ThreadLocalRandom.current().nextInt(1, 129), identity)
        .runWith(toSeq)
        .futureValue should ===(1 to 6)
    }

    "work with value presented failed sources" in {
      val ex = new BoomException
      Source(
        List(
          Source.empty[Int],
          Source.single(1),
          Source.empty[Int],
          Source(List(2, 3, 4)),
          Source.future(Future.failed(ex)),
          Source.lazyFuture(() => Future.successful(5))))
        .flatMapConcat(ThreadLocalRandom.current().nextInt(1, 129), identity)
        .onErrorComplete[BoomException]()
        .runWith(toSeq)
        .futureValue should ===(1 to 4)
    }

    "work with value presented sources when demands slow" in {
      val prob = Source(
        List(Source.empty[Int], Source.single(1), Source(List(2, 3, 4)), Source.lazyFuture(() => Future.successful(5))))
        .flatMapConcat(ThreadLocalRandom.current().nextInt(1, 129), identity)
        .runWith(TestSink())

      prob.request(1)
      prob.expectNext(1)
      prob.expectNoMessage(1.seconds)
      prob.request(2)
      prob.expectNext(2, 3)
      prob.expectNoMessage(1.seconds)
      prob.request(2)
      prob.expectNext(4, 5)
      prob.expectComplete()
    }

    "can do pre materialization when parallelism > 1" in {
      val materializationCounter = new AtomicInteger(0)
      val randomParallelism = ThreadLocalRandom.current().nextInt(4, 65)
      val prob = Source(1 to (randomParallelism * 3))
        .flatMapConcat(
          randomParallelism,
          value => {
            Source
              .lazySingle(() => {
                materializationCounter.incrementAndGet()
                value
              })
              .buffer(1, overflowStrategy = OverflowStrategy.backpressure)
          })
        .runWith(TestSink())

      expectNoMessage(1.seconds)
      materializationCounter.get() shouldBe 0

      prob.request(1)
      prob.expectNext(1.seconds, 1)
      expectNoMessage(1.seconds)
      materializationCounter.get() shouldBe (randomParallelism + 1)
      materializationCounter.set(0)

      prob.request(2)
      prob.expectNextN(List(2, 3))
      expectNoMessage(1.seconds)
      materializationCounter.get() shouldBe 2
      materializationCounter.set(0)

      prob.request(randomParallelism - 3)
      prob.expectNextN(4 to randomParallelism)
      expectNoMessage(1.seconds)
      materializationCounter.get() shouldBe (randomParallelism - 3)
      materializationCounter.set(0)

      prob.request(randomParallelism)
      prob.expectNextN(randomParallelism + 1 to randomParallelism * 2)
      expectNoMessage(1.seconds)
      materializationCounter.get() shouldBe randomParallelism
      materializationCounter.set(0)

      prob.request(randomParallelism)
      prob.expectNextN(randomParallelism * 2 + 1 to randomParallelism * 3)
      expectNoMessage(1.seconds)
      materializationCounter.get() shouldBe 0
      prob.expectComplete()
    }

  }

}

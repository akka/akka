/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.pattern
import akka.stream.{ ActorAttributes, ActorMaterializer, Supervision }
import akka.stream.impl.ReactiveStreamsCompliance
import akka.stream.testkit.TestSubscriber.Probe
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.duration._

class FlowScanAsyncSpec extends StreamSpec {

  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext = materializer.executionContext

  "A ScanAsync" must {

    val sumScanFlow = Flow[Int].scanAsync(0) { (accumulator, next) ⇒
      Future(accumulator + next)
    }

    "work with a empty source" in {
      Source.empty[Int]
        .via(sumScanFlow)
        .runWith(TestSink.probe[Int])
        .request(1)
        .expectNext(0)
        .expectComplete()
    }

    "work with a single source" in {
      Source.single(1)
        .via(sumScanFlow)
        .runWith(TestSink.probe[Int])
        .request(2)
        .expectNext(0, 1)
        .expectComplete()
    }

    "work with a large source" in {
      val elements = 1 to 100000
      val expectedSum = elements.sum
      val eventualActual: Future[Int] = Source(elements)
        .via(sumScanFlow)
        .runWith(Sink.last)
      whenReady(eventualActual) { actual ⇒ assert(actual === expectedSum) }
    }

    "work with slow futures" in {
      val delay = 500.milliseconds
      val delayedFutureScanFlow = Flow[Int].scanAsync(0) { (accumulator, next) ⇒
        pattern.after(delay, system.scheduler)(Future.successful(accumulator + next))
      }
      val elements = 1 :: 1 :: Nil
      Source(elements)
        .via(delayedFutureScanFlow)
        .runWith(TestSink.probe[Int])
        .request(3)
        .expectNext(100.milliseconds, 0)
        .expectNext(1.second, 1)
        .expectNext(1.second, 2)
        .expectComplete()
    }

    "throw error with a failed source" in {
      val expected = Utils.TE("failed source")
      Source.failed[Int](expected)
        .via(sumScanFlow)
        .runWith(TestSink.probe[Int])
        .expectSubscriptionAndError(expected)
    }

    "with the restarting decider" should {
      "skip error values with a failed scan" in {
        val elements = 1 :: -1 :: 1 :: Nil
        whenFailedScan(elements, 0, decider = Supervision.restartingDecider)
          .expectNext(1, 1)
          .expectComplete()
      }

      "emit zero with a failed future" in {
        val elements = 1 :: -1 :: 1 :: Nil
        whenFailedFuture(elements, 0, decider = Supervision.restartingDecider)
          .expectNext(1, 1)
          .expectComplete()
      }
    }

    "with the resuming decider" should {
      "skip values with a failed scan" in {
        val elements = 1 :: -1 :: 1 :: Nil
        whenFailedScan(elements, 0, decider = Supervision.resumingDecider)
          .expectNext(1, 2)
          .expectComplete()
      }

      "skip values with a failed future" in {
        val elements = 1 :: -1 :: 1 :: Nil
        whenFailedFuture(elements, 0, decider = Supervision.resumingDecider)
          .expectNext(1, 2)
          .expectComplete()
      }
    }

    "with the stopping decider" should {
      "throw error with a failed scan function" in {
        val expected = Utils.TE("failed scan function")
        val elements = -1 :: Nil
        whenFailedScan(elements, 0, expected)
          .expectError(expected)
      }

      "throw error with a failed future" in {
        val expected = Utils.TE("failed future generated from scan function")
        val elements = -1 :: Nil
        whenFailedFuture(elements, 0, expected)
          .expectError(expected)
      }

      "throw error with a null element" in {
        val expectedMessage = ReactiveStreamsCompliance.ElementMustNotBeNullMsg
        val elements = "null" :: Nil
        val actual = whenNullElement(elements, "")
          .expectError()
        assert(actual.getClass === classOf[NullPointerException])
        assert(actual.getMessage === expectedMessage)
      }
    }

    def whenFailedScan(
      elements:  immutable.Seq[Int],
      zero:      Int,
      throwable: Throwable           = new Exception("non fatal exception"),
      decider:   Supervision.Decider = Supervision.stoppingDecider): Probe[Int] = {
      val failedScanFlow = Flow[Int].scanAsync(zero) { (accumulator: Int, next: Int) ⇒
        if (next >= 0) Future(accumulator + next)
        else throw throwable
      }
      Source(elements)
        .via(failedScanFlow)
        .withAttributes(ActorAttributes.supervisionStrategy(decider))
        .runWith(TestSink.probe[Int])
        .request(elements.size + 1)
        .expectNext(zero)
    }

    def whenFailedFuture(
      elements:  immutable.Seq[Int],
      zero:      Int,
      throwable: Throwable           = new Exception("non fatal exception"),
      decider:   Supervision.Decider = Supervision.stoppingDecider): Probe[Int] = {
      val failedFutureScanFlow = Flow[Int].scanAsync(zero) { (accumulator: Int, next: Int) ⇒
        if (next >= 0) Future(accumulator + next)
        else Future.failed(throwable)
      }
      Source(elements)
        .via(failedFutureScanFlow)
        .withAttributes(ActorAttributes.supervisionStrategy(decider))
        .runWith(TestSink.probe[Int])
        .request(elements.size + 1)
        .expectNext(zero)
    }

    def whenNullElement(
      elements: immutable.Seq[String],
      zero:     String,
      decider:  Supervision.Decider   = Supervision.stoppingDecider): Probe[String] = {
      val nullFutureScanFlow: Flow[String, String, _] = Flow[String].scanAsync(zero) { (_: String, next: String) ⇒
        if (next != "null") Future(next)
        else Future(null)
      }
      Source(elements)
        .via(nullFutureScanFlow)
        .withAttributes(ActorAttributes.supervisionStrategy(decider))
        .runWith(TestSink.probe[String])
        .request(elements.size + 1)
        .expectNext(zero)
    }

  }

}

/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.testkit.AkkaSpec
import akka.stream.ActorMaterializer
import scala.concurrent._
import scala.concurrent.duration._
import org.scalatest.concurrent.ScalaFutures
import org.scalactic.ConversionCheckedTripleEquals
import akka.stream.testkit.TestPublisher
import org.scalatest.exceptions.TestFailedException
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit.TestLatch

class FlowFlattenMergeSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  def src10(i: Int) = Source(i until (i + 10))
  def blocked = Source.fromFuture(Promise[Int].future)

  val toSeq = Flow[Int].grouped(1000).toMat(Sink.head)(Keep.right)
  val toSet = toSeq.mapMaterializedValue(_.map(_.toSet))

  "A FattenMerge" must {

    "work in the nominal case" in {
      Source(List(src10(0), src10(10), src10(20), src10(30)))
        .flatMapMerge(4, identity)
        .runWith(toSet)
        .futureValue should ===((0 until 40).toSet)
    }

    "not be held back by one slow stream" in {
      Source(List(src10(0), src10(10), blocked, src10(20), src10(30)))
        .flatMapMerge(3, identity)
        .take(40)
        .runWith(toSet)
        .futureValue should ===((0 until 40).toSet)
    }

    "respect breadth" in {
      val seq = Source(List(src10(0), src10(10), src10(20), blocked, blocked, src10(30)))
        .flatMapMerge(3, identity)
        .take(40)
        .runWith(toSeq)
        .futureValue

      seq.take(30).toSet should ===((0 until 30).toSet)
      seq.drop(30).toSet should ===((30 until 40).toSet)
    }

    "propagate early failure from main stream" in {
      val ex = new Exception("buh")
      intercept[TestFailedException] {
        Source.failed(ex)
          .flatMapMerge(1, identity)
          .runWith(Sink.head)
          .futureValue
      }.cause.get should ===(ex)
    }

    "propagate late failure from main stream" in {
      val ex = new Exception("buh")
      intercept[TestFailedException] {
        (Source(List(blocked, blocked)) ++ Source.failed(ex))
          .flatMapMerge(10, identity)
          .runWith(Sink.head)
          .futureValue
      }.cause.get should ===(ex)
    }

    "propagate failure from map function" in {
      val ex = new Exception("buh")
      intercept[TestFailedException] {
        Source(1 to 3)
          .flatMapMerge(10, i ⇒ if (i == 3) throw ex else blocked)
          .runWith(Sink.head)
          .futureValue
      }.cause.get should ===(ex)
    }

    "bubble up substream exceptions" in {
      val ex = new Exception("buh")
      val result = intercept[TestFailedException] {
        Source(List(blocked, blocked, Source.failed(ex)))
          .flatMapMerge(10, identity)
          .runWith(Sink.head)
          .futureValue
      }.cause.get should ===(ex)
    }

    "cancel substreams when failing from main stream" in {
      val p1, p2 = TestPublisher.probe[Int]()
      val ex = new Exception("buh")
      val p = Promise[Source[Int, NotUsed]]
      (Source(List(Source.fromPublisher(p1), Source.fromPublisher(p2))) ++ Source.fromFuture(p.future))
        .flatMapMerge(5, identity)
        .runWith(Sink.head)
      p1.expectRequest()
      p2.expectRequest()
      p.failure(ex)
      p1.expectCancellation()
      p2.expectCancellation()
    }

    "cancel substreams when failing from substream" in {
      val p1, p2 = TestPublisher.probe[Int]()
      val ex = new Exception("buh")
      val p = Promise[Int]
      Source(List(Source.fromPublisher(p1), Source.fromPublisher(p2), Source.fromFuture(p.future)))
        .flatMapMerge(5, identity)
        .runWith(Sink.head)
      p1.expectRequest()
      p2.expectRequest()
      p.failure(ex)
      p1.expectCancellation()
      p2.expectCancellation()
    }

    "cancel substreams when failing map function" in {
      val p1, p2 = TestPublisher.probe[Int]()
      val ex = new Exception("buh")
      val latch = TestLatch()
      Source(1 to 3)
        .flatMapMerge(10, {
          case 1 ⇒ Source.fromPublisher(p1)
          case 2 ⇒ Source.fromPublisher(p2)
          case 3 ⇒
            Await.ready(latch, 3.seconds)
            throw ex
        })
        .runWith(Sink.head)
      p1.expectRequest()
      p2.expectRequest()
      latch.countDown()
      p1.expectCancellation()
      p2.expectCancellation()
    }

    "cancel substreams when being cancelled" in {
      val p1, p2 = TestPublisher.probe[Int]()
      val ex = new Exception("buh")
      val sink = Source(List(Source.fromPublisher(p1), Source.fromPublisher(p2)))
        .flatMapMerge(5, identity)
        .runWith(TestSink.probe)
      sink.request(1)
      p1.expectRequest()
      p2.expectRequest()
      sink.cancel()
      p1.expectCancellation()
      p2.expectCancellation()
    }

    "work with many concurrently queued events" in {
      val p = Source((0 until 100).map(i ⇒ src10(10 * i)))
        .flatMapMerge(Int.MaxValue, identity)
        .runWith(TestSink.probe)
      p.within(1.second) {
        p.ensureSubscription()
        p.expectNoMsg()
      }
      val elems = p.within(1.second)((1 to 1000).map(i ⇒ p.requestNext()).toSet)
      p.expectComplete()
      elems should ===((0 until 1000).toSet)
    }

  }
}

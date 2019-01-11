/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream._
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._
import scala.concurrent.{ Future, Promise }

class LazyFlowSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 1, maxSize = 1)
  implicit val materializer = ActorMaterializer(settings)

  val ex = TE("")

  "A LazyFlow" must {
    def mapF(e: Int): () ⇒ Future[Flow[Int, String, NotUsed]] = () ⇒
      Future.successful(Flow.fromFunction[Int, String](i ⇒ (i * e).toString))
    val flowF = Future.successful(Flow[Int])
    "work in happy case" in assertAllStagesStopped {
      val probe = Source(2 to 10)
        .via(Flow.lazyInitAsync[Int, String, NotUsed](mapF(2)))
        .runWith(TestSink.probe[String])
      probe.request(100)
      (2 to 10).map(i ⇒ (i * 2).toString).foreach(probe.expectNext)
    }

    "work with slow flow init" in assertAllStagesStopped {
      val p = Promise[Flow[Int, Int, NotUsed]]()
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val flowProbe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInitAsync[Int, Int, NotUsed](() ⇒ p.future))
        .runWith(TestSink.probe[Int])

      val sourceSub = sourceProbe.expectSubscription()
      flowProbe.request(1)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      sourceProbe.expectNoMsg(200.millis)

      p.success(Flow[Int])
      flowProbe.request(99)
      flowProbe.expectNext(0)
      (1 to 10).foreach(i ⇒ {
        sourceSub.sendNext(i)
        flowProbe.expectNext(i)
      })
      sourceSub.sendComplete()
    }

    "complete when there was no elements in the stream" in assertAllStagesStopped {
      def flowMaker() = flowF
      val probe = Source.empty
        .via(Flow.lazyInitAsync(() ⇒ flowMaker))
        .runWith(TestSink.probe[Int])
      probe.request(1).expectComplete()
    }

    "complete normally when upstream completes BEFORE the stage has switched to the inner flow" in assertAllStagesStopped {
      val promise = Promise[Flow[Int, Int, NotUsed]]
      val (pub, sub) = TestSource.probe[Int]
        .viaMat(Flow.lazyInitAsync(() ⇒ promise.future))(Keep.left)
        .toMat(TestSink.probe)(Keep.both)
        .run()
      sub.request(1)
      pub.sendNext(1).sendComplete()
      promise.success(Flow[Int])
      sub.expectNext(1).expectComplete()
    }

    "complete normally when upstream completes AFTER the stage has switched to the inner flow" in assertAllStagesStopped {
      val (pub, sub) = TestSource.probe[Int]
        .viaMat(Flow.lazyInitAsync(() ⇒ Future.successful(Flow[Int])))(Keep.left)
        .toMat(TestSink.probe)(Keep.both)
        .run()
      sub.request(1)
      pub.sendNext(1)
      sub.expectNext(1)
      pub.sendComplete()
      sub.expectComplete()
    }

    "fail gracefully when flow factory method failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val probe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInitAsync[Int, Int, NotUsed](() ⇒ throw ex))
        .runWith(TestSink.probe[Int])

      val sourceSub = sourceProbe.expectSubscription()
      probe.request(1)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectCancellation()
      probe.expectError(ex)
    }

    "fail gracefully when upstream failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val probe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInitAsync(() ⇒ flowF))
        .runWith(TestSink.probe)

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      probe.request(1)
        .expectNext(0)
      sourceSub.sendError(ex)
      probe.expectError(ex)
    }

    "fail gracefully when factory future failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val flowProbe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInitAsync[Int, Int, NotUsed](() ⇒ Future.failed(ex)))
        .runWith(TestSink.probe)

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      flowProbe.request(1).expectError(ex)
    }

    "cancel upstream when the downstream is cancelled" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val probe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInitAsync[Int, Int, NotUsed](() ⇒ flowF))
        .runWith(TestSink.probe[Int])

      val sourceSub = sourceProbe.expectSubscription()
      probe.request(1)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      probe.expectNext(0)
      probe.cancel()
      sourceSub.expectCancellation()
    }

    "fail correctly when factory throw error" in assertAllStagesStopped {
      val msg = "fail!"
      val matFail = TE(msg)
      val result = Source.single("whatever")
        .viaMat(Flow.lazyInitAsync(() ⇒ throw matFail))(Keep.right)
        .toMat(Sink.ignore)(Keep.left)
        .run()

      ScalaFutures.whenReady(result.failed) { e ⇒
        e.getMessage shouldBe msg
      }
    }
  }

}

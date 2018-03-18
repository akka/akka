/**
 * Copyright (C) 2018-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.ActorAttributes.supervisionStrategy
import akka.stream.Supervision._
import akka.stream._
import akka.stream.stage.{ GraphStageLogic, GraphStageWithMaterializedValue }
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._

class LazyFlowSpec extends StreamSpec {

  val settings = ActorMaterializerSettings(system)
    .withInputBuffer(initialSize = 1, maxSize = 1)
  implicit val materializer = ActorMaterializer(settings)

  val fallback = () ⇒ NotUsed
  val ex = TE("")

  "A LazyFlow" must {
    def mapF(e: Int): Future[Flow[Int, String, NotUsed]] =
      Future.successful(Flow.fromFunction[Int, String](i ⇒ (i * e).toString))
    val flowF = Future.successful(Flow.fromFunction[Int, Int](id ⇒ id))
    "work in happy case" in assertAllStagesStopped {
      val probe = Source(2 to 10)
        .via(Flow.lazyInit[Int, String, NotUsed](mapF, fallback))
        .runWith(TestSink.probe[String])
      probe.request(100)
      (2 to 10).map(i ⇒ (i * 2).toString).foreach(probe.expectNext)
    }

    "work with slow flow init" in assertAllStagesStopped {
      val p = Promise[Flow[Int, Int, NotUsed]]()
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val flowProbe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInit[Int, Int, NotUsed](_ ⇒ p.future, fallback))
        .runWith(TestSink.probe[Int])

      val sourceSub = sourceProbe.expectSubscription()
      flowProbe.request(1)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      sourceProbe.expectNoMsg(200.millis)

      p.success(Flow.fromFunction[Int, Int](id ⇒ id))
      flowProbe.request(99)
      flowProbe.expectNext(0)
      (1 to 10).foreach(i ⇒ {
        sourceSub.sendNext(i)
        flowProbe.expectNext(i)
      })
      sourceSub.sendComplete()
    }

    "complete when there was no elements in the stream" in assertAllStagesStopped {
      def flowMaker(i: Int) = flowF
      val probe = Source.empty
        .via(Flow.lazyInit(flowMaker, () ⇒ 0))
        .runWith(TestSink.probe[Int])
      probe.request(1).expectComplete()
    }

    "complete normally when upstream is completed" in assertAllStagesStopped {
      val probe = Source.single(1)
        .via(Flow.lazyInit[Int, Int, NotUsed](_ ⇒ flowF, fallback))
        .runWith(TestSink.probe[Int])
      probe.request(1)
        .expectNext(1)
        .expectComplete()
    }

    "fail gracefully when flow factory method failed" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val probe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInit[Int, Int, NotUsed](_ ⇒ throw ex, fallback))
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
        .via(Flow.lazyInit[Int, Int, NotUsed](_ ⇒ flowF, fallback))
        .runWith(TestSink.probe[Int])

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
        .via(Flow.lazyInit[Int, Int, NotUsed](_ ⇒ Future.failed(ex), fallback))
        .withAttributes(supervisionStrategy(stoppingDecider))
        .runWith(TestSink.probe[Int])

      val sourceSub = sourceProbe.expectSubscription()
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      flowProbe.request(1).expectError(ex)
    }

    "cancel upstream when the downstream is cancelled" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      val probe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInit[Int, Int, NotUsed](_ ⇒ flowF, fallback))
        .withAttributes(supervisionStrategy(stoppingDecider))
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

    "continue if supervision is resume" in assertAllStagesStopped {
      val sourceProbe = TestPublisher.manualProbe[Int]()
      def flowBuilder(a: Int) = if (a == 0) throw ex else Future.successful(Flow.fromFunction[Int, Int](id ⇒ id))
      val probe = Source.fromPublisher(sourceProbe)
        .via(Flow.lazyInit[Int, Int, NotUsed](flowBuilder, fallback))
        .withAttributes(supervisionStrategy(resumingDecider))
        .runWith(TestSink.probe[Int])

      val sourceSub = sourceProbe.expectSubscription()
      probe.request(1)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(0)
      sourceSub.expectRequest(1)
      sourceSub.sendNext(1)
      probe.expectNext(1)
      probe.cancel()
    }

    "fail correctly when materialization of inner sink fails" in assertAllStagesStopped {
      val matFail = TE("fail!")
      object FailingInnerMat extends GraphStageWithMaterializedValue[FlowShape[String, String], Option[String]] {
        val in = Inlet[String]("in")
        val out = Outlet[String]("out")
        val shape = FlowShape(in, out)
        override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) =
          (new GraphStageLogic(shape) {
            throw matFail
          }, Some("fine"))
      }

      val result = Source.single("whatever")
        .viaMat(Flow.lazyInit(
          _ ⇒ Future.successful(Flow.fromGraph(FailingInnerMat)),
          () ⇒ Some("boom")))(Keep.right)
        .toMat(Sink.ignore)(Keep.left)
        .run()

      result should ===(Some("boom"))
    }
  }

}

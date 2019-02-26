/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicBoolean

import akka.Done
import akka.stream.impl.LazySource
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializer, Attributes, Outlet, SourceShape }
import akka.testkit.DefaultTimeout
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Seq
import scala.concurrent.Future

class LazyInitAsyncSourceSpec extends StreamSpec with DefaultTimeout with ScalaFutures {

  implicit val materializer = ActorMaterializer()

  "A lazy async source" should {
    "work like a normal source, happy path" in assertAllStagesStopped {
      val result = Source.lazyInitAsync(() ⇒ Future.successful(Source(List(1, 2, 3)))).runWith(Sink.seq)

      result.futureValue shouldEqual Seq(1, 2, 3)
    }

    "never construct the source when there was no demand" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val result = Source.lazyInitAsync(() ⇒ { constructed.set(true); Future.successful(Source(List(1, 2, 3))) }).toMat(Sink.fromSubscriber(probe))(Keep.left).run
      probe.cancel()

      result.futureValue shouldEqual None

      constructed.get() shouldEqual false
    }

    "stop consuming when downstream has cancelled" in assertAllStagesStopped {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.lazyInitAsync(() ⇒ Future.successful(Source.fromPublisher(inProbe))).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      outProbe.cancel()
      inProbe.expectCancellation()
    }

    "materialize when the source has been created" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()

      object X

      val matF: Future[Option[X.type]] = Source.lazyInitAsync(
        () ⇒ Future.successful(Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ X)))
        .to(Sink.fromSubscriber(probe))
        .run()

      probe.request(1)
      probe.expectNext(1)
      matF.futureValue shouldEqual Some(X)

      probe.cancel()
    }

    "fail stage when upstream fails" in assertAllStagesStopped {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.lazyInitAsync(() ⇒ Future.successful(Source.fromPublisher(inProbe))).runWith(Sink.fromSubscriber(outProbe))

      val ex = TE("OMG Who set that on fire!?!")
      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      inProbe.sendError(ex)
      outProbe.expectError() shouldEqual ex
    }

    "fail correctly when materialization of inner source fails" in assertAllStagesStopped {
      val ex = TE("failed materialzation")
      object FailingInnerMat extends GraphStage[SourceShape[String]] {
        val out = Outlet[String]("out")
        val shape = SourceShape(out)
        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          throw ex
        }
      }

      val result = Source.lazily(() ⇒ Source.fromGraph(FailingInnerMat)).to(Sink.ignore).run()

      result.failed.futureValue shouldEqual ex

    }

    "fail correctly when future of creating the inner source fails" in assertAllStagesStopped {
      val ex = TE("failed future")

      val result = Source.lazyInitAsync(() ⇒ Future.failed(ex)).to(Sink.ignore).run()

      result.failed.futureValue shouldEqual ex

    }
  }

}

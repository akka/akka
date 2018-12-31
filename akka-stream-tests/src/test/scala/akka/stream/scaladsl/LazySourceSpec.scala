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

class LazySourceSpec extends StreamSpec with DefaultTimeout with ScalaFutures {

  implicit val materializer = ActorMaterializer()

  "A lazy source" should {
    "work like a normal source, happy path" in assertAllStagesStopped {
      val result = Source.fromGraph(LazySource(() ⇒ Source(List(1, 2, 3)))).runWith(Sink.seq)

      result.futureValue should ===(Seq(1, 2, 3))
    }

    "never construct the source when there was no demand" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val result = Source.fromGraph(LazySource { () ⇒ constructed.set(true); Source(List(1, 2, 3)) }).runWith(Sink.fromSubscriber(probe))
      probe.cancel()

      constructed.get() should ===(false)
    }

    "fail the materialized value when downstream cancels without ever consuming any element" in assertAllStagesStopped {
      val matF = Source.fromGraph(LazySource(() ⇒ Source(List(1, 2, 3))))
        .toMat(Sink.cancelled)(Keep.left)
        .run()

      intercept[RuntimeException] {
        matF.futureValue
      }
    }

    "stop consuming when downstream has cancelled" in assertAllStagesStopped {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.fromGraph(LazySource(() ⇒ Source.fromPublisher(inProbe))).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      outProbe.cancel()
      inProbe.expectCancellation()
    }

    "materialize when the source has been created" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()

      val matF: Future[Done] = Source.fromGraph(LazySource { () ⇒
        Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ Done)
      }).to(Sink.fromSubscriber(probe))
        .run()

      matF.value shouldEqual None
      probe.request(1)
      probe.expectNext(1)
      matF.futureValue should ===(Done)

      probe.cancel()
    }

    "fail stage when upstream fails" in assertAllStagesStopped {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.fromGraph(LazySource(() ⇒ Source.fromPublisher(inProbe))).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      inProbe.sendError(TE("OMG Who set that on fire!?!"))
      outProbe.expectError() shouldEqual TE("OMG Who set that on fire!?!")
    }

    "fail correctly when materialization of inner source fails" in assertAllStagesStopped {
      val matFail = TE("fail!")
      object FailingInnerMat extends GraphStage[SourceShape[String]] {
        val out = Outlet[String]("out")
        val shape = SourceShape(out)
        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          throw matFail
        }
      }

      val result = Source.lazily(() ⇒ Source.fromGraph(FailingInnerMat)).to(Sink.ignore).run()

      result.failed.futureValue should ===(matFail)

    }
  }

}

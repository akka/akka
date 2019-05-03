/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicBoolean

import akka.{ Done, NotUsed }
import akka.stream.stage.{ GraphStage, GraphStageLogic }
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.stream.{ ActorMaterializer, Attributes, NeverMaterializedException, Outlet, SourceShape }
import akka.testkit.DefaultTimeout
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Seq
import scala.concurrent.{ Future, Promise }

class LazySourceSpec extends StreamSpec with DefaultTimeout with ScalaFutures {

  implicit val materializer = ActorMaterializer()

  "Source.lazySingle" must {
    "work like a normal source, happy path" in assertAllStagesStopped {
      val (lazyMatVal, seq) = Source.lazySingle(() => 1).toMat(Sink.seq)(Keep.both).run()

      seq.futureValue should ===(Seq(1))
      lazyMatVal.isCompleted should ===(true)
    }

    "never construct the source when there was no demand" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val lazyMatVal: Future[Done] = Source
        .lazySingle { () =>
          constructed.set(true)
          1
        }
        .toMat(Sink.fromSubscriber(probe))(Keep.left)
        .run()
      probe.cancel()

      constructed.get() should ===(false)
      lazyMatVal.isCompleted should ===(false)
    }

    "fail the materialized value when downstream cancels without ever consuming any element" in assertAllStagesStopped {
      val lazyMatVal: Future[Done] = Source.lazySingle(() => 1).toMat(Sink.cancelled)(Keep.left).run()

      lazyMatVal.failed.futureValue shouldBe a[NeverMaterializedException]
    }

    "fail correctly when factory function fails" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val lazyMatVal: Future[Done] = Source.lazySingle(() => throw failure).toMat(Sink.ignore)(Keep.left).run()

      lazyMatVal.failed.futureValue should ===(failure)
    }

  }

  "Source.lazyFuture" must {
    "work like a normal source, happy path, already completed future" in assertAllStagesStopped {
      val (lazyMatVal, seq) = Source.lazyFuture(() => Future.successful(1)).toMat(Sink.seq)(Keep.both).run()

      seq.futureValue should ===(Seq(1))
      lazyMatVal.isCompleted should ===(true)
    }

    "work like a normal source, happy path, completing future" in assertAllStagesStopped {
      val promise = Promise[Int]()
      val (lazyMatVal, seq) = Source.lazyFuture(() => promise.future).toMat(Sink.seq)(Keep.both).run()
      promise.success(1)
      seq.futureValue should ===(Seq(1))
      lazyMatVal.isCompleted should ===(true)
    }

    "never construct the source when there was no demand" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val lazyMatVal: Future[Done] = Source
        .lazySingle { () =>
          constructed.set(true)
          1
        }
        .toMat(Sink.fromSubscriber(probe))(Keep.left)
        .run()
      probe.cancel()

      constructed.get() should ===(false)
      lazyMatVal.isCompleted should ===(false)
    }

    "fail the materialized value when downstream cancels without ever consuming any element" in assertAllStagesStopped {
      val lazyMatVal: Future[Done] = Source.lazySingle(() => 1).toMat(Sink.cancelled)(Keep.left).run()

      lazyMatVal.failed.futureValue shouldBe a[NeverMaterializedException]
    }

    "fail correctly when factory function fails" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val lazyMatVal: Future[Done] = Source.lazyFuture(() => throw failure).toMat(Sink.ignore)(Keep.left).run()

      lazyMatVal.failed.futureValue should ===(failure)
    }

    "fail correctly when factory function returns a failed future" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val lazyMatVal: Future[Done] =
        Source.lazyFuture(() => Future.failed(failure)).toMat(Sink.ignore)(Keep.left).run()

      lazyMatVal.failed.futureValue should ===(failure)
    }

    "fail correctly when factory function returns a future that fails" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val promise = Promise[Int]()
      val lazyMatVal = Source.lazyFuture(() => promise.future).toMat(Sink.ignore)(Keep.left).run()
      promise.failure(failure)
      lazyMatVal.failed.futureValue should ===(failure)
    }
  }

  "Source.lazySource" must {
    "work like a normal source, happy path" in assertAllStagesStopped {
      val result = Source.lazySource(() => Source(List(1, 2, 3))).runWith(Sink.seq)

      result.futureValue should ===(Seq(1, 2, 3))
    }

    "never construct the source when there was no demand" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val result = Source
        .lazySource { () =>
          constructed.set(true); Source(List(1, 2, 3))
        }
        .toMat(Sink.fromSubscriber(probe))(Keep.left)
        .run()
      probe.cancel()

      constructed.get() should ===(false)
      result.isCompleted should ===(false)
    }

    "fail the materialized value when downstream cancels without ever consuming any element" in assertAllStagesStopped {
      val lazyMatVal = Source.lazySource(() => Source(List(1, 2, 3))).toMat(Sink.cancelled)(Keep.left).run()

      lazyMatVal.failed.futureValue shouldBe a[NeverMaterializedException]
    }

    "stop consuming when downstream has cancelled" in assertAllStagesStopped {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.lazySource(() => Source.fromPublisher(inProbe)).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      outProbe.cancel()
      inProbe.expectCancellation()
    }

    "materialize when the source has been created" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()

      val matF: Future[Done] = Source
        .lazySource { () =>
          Source(List(1, 2, 3)).mapMaterializedValue(_ => Done)
        }
        .to(Sink.fromSubscriber(probe))
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

      val lazyMatVal =
        Source.lazySource(() => Source.fromPublisher(inProbe)).toMat(Sink.fromSubscriber(outProbe))(Keep.left).run()

      outProbe.request(1)
      inProbe.expectRequest()

      lazyMatVal.futureValue should ===(NotUsed) // was completed

      inProbe.sendNext(27)
      outProbe.expectNext(27)
      val failure = TE("OMG Who set that on fire!?!")
      inProbe.sendError(failure)
      outProbe.expectError() should ===(failure)
    }

    "fail when lazy source is failed" in assertAllStagesStopped {
      val failure = TE("OMG Who set that on fire!?!")
      val result = Source.lazySource(() => Source.failed(failure)).runWith(Sink.seq)
      result.failed.futureValue should ===(failure)
    }

    "fail correctly when factory function fails" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val lazyMatVal = Source.lazySource(() => throw failure).toMat(Sink.ignore)(Keep.left).run()

      lazyMatVal.failed.futureValue should ===(failure)
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

      val (lazyMatVal, done) =
        Source.lazySource(() => Source.fromGraph(FailingInnerMat)).toMat(Sink.ignore)(Keep.both).run()

      done.failed.futureValue should ===(matFail)
      lazyMatVal.failed.futureValue should ===(matFail)
    }
  }

  "Source.lazyFutureSource" must {
    import materializer.executionContext
    "work like a normal source, happy path" in assertAllStagesStopped {
      val result = Source.lazyFutureSource(() => Future { Source(List(1, 2, 3)) }).runWith(Sink.seq)

      result.futureValue should ===(Seq(1, 2, 3))
    }

    "work like a normal source, happy path, already completed future" in assertAllStagesStopped {
      val result = Source.lazyFutureSource(() => Future.successful { Source(List(1, 2, 3)) }).runWith(Sink.seq)

      result.futureValue should ===(Seq(1, 2, 3))
    }

    "never construct the source when there was no demand" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val result = Source
        .lazyFutureSource { () =>
          Future {
            constructed.set(true)
            Source(List(1, 2, 3))
          };
        }
        .toMat(Sink.fromSubscriber(probe))(Keep.left)
        .run()
      probe.cancel()

      constructed.get() should ===(false)
      result.isCompleted should ===(false)
    }

    "fail the materialized value when downstream cancels without ever consuming any element" in assertAllStagesStopped {
      val lazyMatVal: Future[NotUsed] =
        Source.lazyFutureSource(() => Future { Source(List(1, 2, 3)) }).toMat(Sink.cancelled)(Keep.left).run()

      lazyMatVal.failed.futureValue shouldBe a[NeverMaterializedException]
    }

    "stop consuming when downstream has cancelled" in assertAllStagesStopped {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.lazyFutureSource(() => Future { Source.fromPublisher(inProbe) }).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      outProbe.cancel()
      inProbe.expectCancellation()
    }

    "materialize when the source has been created" in assertAllStagesStopped {
      val probe = TestSubscriber.probe[Int]()

      val matF: Future[Done] = Source
        .lazyFutureSource { () =>
          Future {
            Source(List(1, 2, 3)).mapMaterializedValue(_ => Done)
          }
        }
        .to(Sink.fromSubscriber(probe))
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

      val lazyMatVal: Future[NotUsed] =
        Source
          .lazyFutureSource(() =>
            Future {
              Source.fromPublisher(inProbe)
            })
          .toMat(Sink.fromSubscriber(outProbe))(Keep.left)
          .run()

      outProbe.request(1)
      lazyMatVal.futureValue should ===(NotUsed) // but completed
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      val failure = TE("OMG Who set that on fire!?!")
      inProbe.sendError(failure)
      outProbe.expectError() should ===(failure)
    }

    "fail correctly when factory function fails" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val lazyMatVal: Future[NotUsed] =
        Source.lazyFutureSource[Int, NotUsed](() => throw failure).toMat(Sink.ignore)(Keep.left).run()

      lazyMatVal.failed.futureValue should ===(failure)
    }

    "fail correctly when factory function returns a failed future" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val lazyMatVal: Future[NotUsed] =
        Source.lazyFutureSource[Int, NotUsed](() => Future.failed(failure)).toMat(Sink.ignore)(Keep.left).run()

      lazyMatVal.failed.futureValue should ===(failure)
    }

    "fail correctly when factory function returns a future that fails" in assertAllStagesStopped {
      val failure = TE("couldn't create")
      val promise = Promise[Source[Int, NotUsed]]()
      val lazyMatVal: Future[NotUsed] =
        Source.lazyFutureSource(() => promise.future).toMat(Sink.ignore)(Keep.left).run()
      promise.failure(failure)
      lazyMatVal.failed.futureValue should ===(failure)
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

      val (lazyMatVal, done) =
        Source
          .lazyFutureSource(() =>
            Future {
              Source.fromGraph(FailingInnerMat)
            })
          .toMat(Sink.ignore)(Keep.both)
          .run()

      done.failed.futureValue should ===(matFail)
      lazyMatVal.failed.futureValue should ===(matFail)
    }
  }

}

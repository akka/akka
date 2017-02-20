/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import scala.concurrent.{ Await, ExecutionContext, Future, Promise }
import scala.concurrent.duration._

import java.util.concurrent.CompletionStage
import scala.compat.java8.FutureConverters._

import akka.NotUsed
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, OutHandler }
import akka.stream.scaladsl._
import akka.stream.impl.fusing.GraphStages.FutureFlattenSource

import org.scalatest.concurrent.PatienceConfiguration.Timeout

import akka.testkit.EventFilter
import akka.stream.testkit.{ StreamSpec, TestSubscriber }
import akka.stream.testkit.Utils.assertAllStagesStopped

class FutureFlattenSpec extends StreamSpec {
  implicit val materializer = ActorMaterializer()

  "Future source" must {
    {
      implicit def ec = materializer.executionContext

      "flatten elements" in assertAllStagesStopped {
        val subSource: Source[Int, String] =
          Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ "foo")

        val futureSource = new FutureFlattenSource(Future(subSource))
        val source: Source[Int, Future[String]] = Source.fromGraph(futureSource)

        val materialized = Promise[String]()
        val watched: Source[Int, NotUsed] = source.watchTermination() { (m, d) ⇒
          materialized.completeWith(d.flatMap(_ ⇒ m))
          NotUsed
        }

        val p = watched.runWith(Sink asPublisher false)
        val c = TestSubscriber.manualProbe[Int]()
        p.subscribe(c)

        val sub = c.expectSubscription()
        sub.request(5)

        c.expectNext(1)
        c.expectNext(2)
        c.expectNext(3)

        c.expectComplete()

        materialized.future.futureValue(Timeout(3.seconds)) should ===("foo")
      }

      "flatten elements from a completion stage" in assertAllStagesStopped {
        val subSource: Graph[SourceShape[Int], Int] =
          Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ 1)

        val future = Future(subSource)
        val stage: CompletionStage[Graph[SourceShape[Int], Int]] = future.toJava
        val g = Source.fromSourceCompletionStage(stage)

        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat.toScala.futureValue should ===(1)
        fut.futureValue should ===(List(1, 2, 3))
      }
    }

    "be cancelled before the underlying Future completes" in {
      assertAllStagesStopped {
        val promise = Promise[Source[Int, Int]]()
        val aside = Promise[Int]()
        val result = Promise[akka.Done]()
        def futureSource = Source.fromFutureSource(
          promise.future).map { i ⇒
          aside.success(i); i // should never occur
        }.watchTermination[Unit]() {
          case (_, res) ⇒ result.completeWith(res); ()
        }

        futureSource.runWith(Sink.cancelled) should ===(NotUsed)
        result.future.futureValue should ===(akka.Done)
        aside.future.isCompleted should ===(false)
      }
    }

    "fails as the underlying Future is failed" in {
      assertAllStagesStopped {
        val promise = Promise[Source[Int, Int]]()
        val result = Promise[akka.Done]()
        def futureSource = Source.fromFutureSource(promise.future)
        def sink = Sink.fold[Int, Int](1)(_ * _)

        promise.failure(new Exception("Foo"))

        futureSource.runWith(sink).failed.map(_.getMessage)(
          materializer.executionContext).futureValue should ===("Foo")
      }
    }

    "applies back-pressure according future completion" in {
      assertAllStagesStopped {
        val probe = TestSubscriber.probe[Int]()
        val underlying = Iterator.iterate(1)(_ + 1).take(3)
        val promise = Promise[Source[Int, NotUsed]]()
        val first = Promise[Unit]()
        lazy val futureSource =
          Source.fromFutureSource(promise.future).map {
            case 1 ⇒
              first.success({}); 11
            case f ⇒ (f * 10) + 1
          }

        futureSource.runWith(Sink asPublisher true).subscribe(probe)
        promise.isCompleted should ===(false)

        val sub = probe.expectSubscription()

        sub.request(5)

        promise.success(Source.fromIterator(() ⇒ underlying))

        // First value
        probe.expectNext(11)
        first.future.futureValue should ===({})

        probe.expectNext(21)
        probe.expectNext(31)
        probe.expectComplete()

        first.isCompleted should ===(true)
      }
    }

    "fail when the future source materialization fails" in {
      implicit def ec = materializer.executionContext

      assertAllStagesStopped {
        def underlying = Future(Source.single(100L).
          mapMaterializedValue[String](_ ⇒ sys.error("MatEx")))

        val aside = Promise[Long]()
        def futureSource: Source[Long, Future[String]] =
          Source.fromFutureSource(underlying).
            map { i ⇒ aside.success(i); i }

        def graph = futureSource.toMat(Sink.last) { (m, _) ⇒ m }

        graph.run().failed.map(_.getMessage).futureValue should ===("MatEx")
        aside.future.futureValue should ===(100L)
      }
    }
  }
}

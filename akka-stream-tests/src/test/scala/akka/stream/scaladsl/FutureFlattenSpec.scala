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
  val materializer = ActorMaterializer()
  //implicit def ec: ExecutionContext = materializer.executionContext

  "Future source" must {
    "use default materializer" when {
      implicit def m = materializer
      implicit def ec = materializer.executionContext

      "flattening elements" in assertAllStagesStopped {
        val subSource: Source[Int, String] =
          Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ "foo")

        val futureSource = new FutureFlattenSource(Future(subSource))
        val source: Source[Int, Future[String]] = Source.fromGraph(futureSource)

        val materialized = Promise[String]()
        val watched: Source[Int, NotUsed] = source.watchTermination() { (m, d) ⇒
          materialized.completeWith(d.flatMap(_ ⇒ m))
          NotUsed
        }

        val p = watched.runWith(Sink.asPublisher(false))
        val c = TestSubscriber.manualProbe[Int]()
        p.subscribe(c)

        val sub = c.expectSubscription()
        sub.request(5)

        c.expectNext(1)
        c.expectNext(2)
        c.expectNext(3)

        c.expectComplete()

        Await.result(materialized.future, 3.seconds) should ===("foo")
      }
    }

    "use a materializer without auto-fusing" when {
      implicit val noFusing = ActorMaterializer(
        ActorMaterializerSettings(system).withAutoFusing(false))
      implicit def ec = noFusing.executionContext

      val tooDeepForStack = 50000

      // Seen tests run in 9-10 seconds, these test cases are heavy on the GC
      val veryPatient = Timeout(20.seconds)

      "flattening from a future graph" in assertAllStagesStopped {
        val g = Source.fromFutureGraph(Future {
          Thread.sleep(2000)
          Fusing.aggressive((1 to tooDeepForStack).
            foldLeft(Source.single(42).mapMaterializedValue(_ ⇒ 1))(
              (f, i) ⇒ f.map(identity)))
        })

        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat.futureValue(veryPatient) should ===(1)
        fut.futureValue(veryPatient) should ===(List(42))
      }

      "flattening from a completion stage" in assertAllStagesStopped {
        val future: Future[Graph[SourceShape[Int], Int]] = Future {
          Fusing.aggressive((1 to tooDeepForStack).
            foldLeft(Source.single(43).mapMaterializedValue(_ ⇒ 1))(
              (f, i) ⇒ f.map(identity)))
        }
        val stage: CompletionStage[Graph[SourceShape[Int], Int]] = future.toJava
        val g = Source.fromGraphCompletionStage(stage)

        val (mat, fut) = g.toMat(Sink.seq)(Keep.both).run()
        mat.toScala.futureValue(veryPatient) should ===(1)
        fut.futureValue(veryPatient) should ===(List(43))
      }
    }

    // TODO: downstream cancels before the future is completed
    // TODO: the future is completed with a failure
    // TODO: downstream is applying backpressure when the future completes
    // TODO: the future is completed with a graph that fails to materialize (throws exception)
  }

  "ActorGraphInterpreter" must {
    implicit def m = materializer
    implicit def ec = materializer.executionContext

    "be able to properly report errors if an error happens for an already completed stage" in {

      val failyStage = new GraphStage[SourceShape[Int]] {
        override val shape: SourceShape[Int] =
          new SourceShape(Outlet[Int]("test.out"))

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

          setHandler(shape.out, new OutHandler {
            override def onPull(): Unit = {
              completeStage()
              // This cannot be propagated now since the stage is already closed
              push(shape.out, -1)
            }
          })

        }
      }

      EventFilter[IllegalArgumentException](
        pattern = "Error in stage.*", occurrences = 1).intercept {
        Await.result(Source.fromFutureGraph(Future(failyStage)).
          runWith(Sink.ignore), 3.seconds)
      }
    }
  }
}

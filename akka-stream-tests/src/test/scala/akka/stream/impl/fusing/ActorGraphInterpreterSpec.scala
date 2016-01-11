/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.Utils._
import akka.testkit.EventFilter

import scala.concurrent.Await
import scala.concurrent.duration._

class ActorGraphInterpreterSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()

  "ActorGraphInterpreter" must {

    "be able to interpret a simple identity graph stage" in assertAllStagesStopped {
      val identity = GraphStages.identity[Int]

      Await.result(
        Source(1 to 100).via(identity).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)

    }

    "be able to reuse a simple identity graph stage" in assertAllStagesStopped {
      val identity = GraphStages.identity[Int]

      Await.result(
        Source(1 to 100)
          .via(identity)
          .via(identity)
          .via(identity)
          .grouped(200)
          .runWith(Sink.head),
        3.seconds) should ===(1 to 100)
    }

    "be able to interpret a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out1, grab(in1))
            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out2, grab(in2))
            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in1)
            override def onDownstreamFinish(): Unit = cancel(in1)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in2)
            override def onDownstreamFinish(): Unit = cancel(in2)
          })
        }

        override def toString = "IdentityBidi"
      }

      val identity = BidiFlow.fromGraph(identityBidi).join(Flow[Int].map { x ⇒ x })

      Await.result(
        Source(1 to 10).via(identity).grouped(100).runWith(Sink.head),
        3.seconds) should ===(1 to 10)

    }

    "be able to interpret and reuse a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out1, grab(in1))

            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out2, grab(in2))

            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in1)

            override def onDownstreamFinish(): Unit = cancel(in1)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in2)

            override def onDownstreamFinish(): Unit = cancel(in2)
          })
        }

        override def toString = "IdentityBidi"
      }

      val identityBidiF = BidiFlow.fromGraph(identityBidi)
      val identity = (identityBidiF atop identityBidiF atop identityBidiF).join(Flow[Int].map { x ⇒ x })

      Await.result(
        Source(1 to 10).via(identity).grouped(100).runWith(Sink.head),
        3.seconds) should ===(1 to 10)

    }

    "be able to interpret and resuse a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out1, grab(in1))

            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out2, grab(in2))

            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in1)

            override def onDownstreamFinish(): Unit = cancel(in1)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in2)

            override def onDownstreamFinish(): Unit = cancel(in2)
          })
        }

        override def toString = "IdentityBidi"
      }

      val identityBidiF = BidiFlow.fromGraph(identityBidi)
      val identity = (identityBidiF atop identityBidiF atop identityBidiF).join(Flow[Int].map { x ⇒ x })

      Await.result(
        Source(1 to 10).via(identity).grouped(100).runWith(Sink.head),
        3.seconds) should ===(1 to 10)

    }

    "be able to interpret a rotated identity bidi stage" in assertAllStagesStopped {
      // This is a "rotated" identity BidiStage, as it loops back upstream elements
      // to its upstream, and loops back downstream elementd to its downstream.

      val rotatedBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in1, new InHandler {
            override def onPush(): Unit = push(out2, grab(in1))

            override def onUpstreamFinish(): Unit = complete(out2)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = push(out1, grab(in2))

            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(out1, new OutHandler {
            override def onPull(): Unit = pull(in2)

            override def onDownstreamFinish(): Unit = cancel(in2)
          })

          setHandler(out2, new OutHandler {
            override def onPull(): Unit = pull(in1)

            override def onDownstreamFinish(): Unit = cancel(in1)
          })
        }

        override def toString = "IdentityBidi"
      }

      val takeAll = Flow[Int].grouped(200).toMat(Sink.head)(Keep.right)

      val (f1, f2) = RunnableGraph.fromGraph(GraphDSL.create(takeAll, takeAll)(Keep.both) { implicit b ⇒
        (out1, out2) ⇒
          import GraphDSL.Implicits._
          val bidi = b.add(rotatedBidi)

          Source(1 to 10) ~> bidi.in1
          out2 <~ bidi.out2

          bidi.in2 <~ Source(1 to 100)
          bidi.out1 ~> out1
          ClosedShape
      }).run()

      Await.result(f1, 3.seconds) should ===(1 to 100)
      Await.result(f2, 3.seconds) should ===(1 to 10)
    }

    "be able to properly report errors if an error happens for an already completed stage" in {

      val failyStage = new GraphStage[SourceShape[Int]] {
        override val shape: SourceShape[Int] = new SourceShape(Outlet[Int]("test.out"))

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

      EventFilter[IllegalArgumentException](message = "Error after stage was closed.", occurrences = 1).intercept {
        Await.result(Source.fromGraph(failyStage).runWith(Sink.ignore), 3.seconds)
      }

    }

  }
}

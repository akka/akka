/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.testkit.AkkaSpec
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.impl.fusing._
import akka.stream.impl.fusing.GraphInterpreter._

class GraphStageLogicSpec extends GraphInterpreterSpecKit {

  implicit val mat = ActorMaterializer()

  object emit1234 extends GraphStage[FlowShape[Int, Int]] {
    val in = Inlet[Int]("in")
    val out = Outlet[Int]("out")
    override val shape = FlowShape(in, out)
    override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
      setHandler(in, eagerTerminateInput)
      setHandler(out, eagerTerminateOutput)
      override def preStart(): Unit = {
        emit(out, 1, () ⇒ emit(out, 2))
        emit(out, 3, () ⇒ emit(out, 4))
      }
    }
  }

  object emit5678 extends GraphStage[FlowShape[Int, Int]] {
    val in = Inlet[Int]("in")
    val out = Outlet[Int]("out")
    override val shape = FlowShape(in, out)
    override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onUpstreamFinish(): Unit = {
          emit(out, 5, () ⇒ emit(out, 6))
          emit(out, 7, () ⇒ emit(out, 8))
          completeStage()
        }
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }

  object passThrough extends GraphStage[FlowShape[Int, Int]] {
    val in = Inlet[Int]("in")
    val out = Outlet[Int]("out")
    override val shape = FlowShape(in, out)
    override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onUpstreamFinish(): Unit = complete(out)
      })
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
    }
  }

  class FusedGraph[S <: Shape](ga: GraphAssembly, s: S, a: Attributes = Attributes.none) extends Graph[S, Unit] {
    override def shape = s
    override val module = GraphModule(ga, s, a)
    override def withAttributes(attr: Attributes) = new FusedGraph(ga, s, attr)
  }

  "A GraphStageLogic" must {

    "emit all things before completing" in {

      Source.empty.via(emit1234.named("testStage")).runWith(TestSink.probe)
        .request(5)
        .expectNext(1, 2, 3, 4)
        .expectComplete()

    }

    "emit all things before completing with two fused stages" in new Builder {
      val g = new FusedGraph(
        builder(emit1234, emit5678)
          .connect(Upstream, emit1234.in)
          .connect(emit1234.out, emit5678.in)
          .connect(emit5678.out, Downstream)
          .buildAssembly(),
        FlowShape(emit1234.in, emit5678.out))

      Source.empty.via(g).runWith(TestSink.probe)
        .request(9)
        .expectNextN(1 to 8)
        .expectComplete()
    }

    "emit all things before completing with three fused stages" in new Builder {
      val g = new FusedGraph(
        builder(emit1234, passThrough, emit5678)
          .connect(Upstream, emit1234.in)
          .connect(emit1234.out, passThrough.in)
          .connect(passThrough.out, emit5678.in)
          .connect(emit5678.out, Downstream)
          .buildAssembly(),
        FlowShape(emit1234.in, emit5678.out))

      Source.empty.via(g).runWith(TestSink.probe)
        .request(9)
        .expectNextN(1 to 8)
        .expectComplete()
    }

  }

}
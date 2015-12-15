/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import akka.stream.testkit.AkkaSpec
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.testkit.Utils.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.impl.fusing._
import akka.stream.impl.fusing.GraphInterpreter._
import org.scalactic.ConversionCheckedTripleEquals
import scala.concurrent.duration.Duration

class GraphStageLogicSpec extends AkkaSpec with GraphInterpreterSpecKit with ConversionCheckedTripleEquals {

  implicit val materializer = ActorMaterializer()

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
    override val module = GraphModule(ga, s, a, ga.stages.map(_.module))
    override def withAttributes(attr: Attributes) = new FusedGraph(ga, s, attr)
  }

  "A GraphStageLogic" must {

    "emit all things before completing" in assertAllStagesStopped {

      Source.empty.via(emit1234.named("testStage")).runWith(TestSink.probe)
        .request(5)
        .expectNext(1, 2, 3, 4)
        .expectComplete()

    }

    "emit all things before completing with two fused stages" in assertAllStagesStopped {
      new Builder {
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
    }

    "emit all things before completing with three fused stages" in assertAllStagesStopped {
      new Builder {
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

    "invoke lifecycle hooks in the right order" in assertAllStagesStopped {
      val g = new GraphStage[FlowShape[Int, Int]] {
        val in = Inlet[Int]("in")
        val out = Outlet[Int]("out")
        override val shape = FlowShape(in, out)
        override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
          setHandler(in, eagerTerminateInput)
          setHandler(out, new OutHandler {
            override def onPull(): Unit = {
              completeStage()
              testActor ! "pulled"
            }
          })
          override def preStart(): Unit = testActor ! "preStart"
          override def postStop(): Unit = testActor ! "postStop"
        }
      }
      Source.single(1).via(g).runWith(Sink.ignore)
      expectMsg("preStart")
      expectMsg("pulled")
      expectMsg("postStop")
    }

    "not double-terminate a single stage" in new Builder {
      object g extends GraphStage[FlowShape[Int, Int]] {
        val in = Inlet[Int]("in")
        val out = Outlet[Int]("out")
        override val shape = FlowShape(in, out)
        override def createLogic(attr: Attributes) = new GraphStageLogic(shape) {
          setHandler(in, eagerTerminateInput)
          setHandler(out, eagerTerminateOutput)
          override def postStop(): Unit = testActor ! "postStop2"
        }
      }

      builder(g, passThrough)
        .connect(Upstream, g.in)
        .connect(g.out, passThrough.in)
        .connect(passThrough.out, Downstream)
        .init()

      interpreter.complete(0)
      interpreter.cancel(1)
      interpreter.execute(2)

      expectMsg("postStop2")
      expectNoMsg(Duration.Zero)

      interpreter.isCompleted should ===(false)
      interpreter.isSuspended should ===(false)
      interpreter.isStageCompleted(interpreter.logics(0)) should ===(true)
      interpreter.isStageCompleted(interpreter.logics(1)) should ===(false)
    }

  }

}

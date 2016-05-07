/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.stream.stage.GraphStageLogic.{ EagerTerminateOutput, EagerTerminateInput }
import akka.testkit.AkkaSpec
import akka.stream._
import akka.stream.Fusing.aggressive
import akka.stream.scaladsl._
import akka.stream.stage._
import akka.stream.testkit.Utils.assertAllStagesStopped
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.impl.fusing._
import scala.concurrent.duration.Duration

class GraphStageLogicSpec extends AkkaSpec with GraphInterpreterSpecKit {

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

  object emitEmptyIterable extends GraphStage[SourceShape[Int]] {
    val out = Outlet[Int]("out")
    override val shape = SourceShape(out)
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

      setHandler(out, new OutHandler {
        override def onPull(): Unit = emitMultiple(out, Iterator.empty, () ⇒ emit(out, 42, () ⇒ completeStage()))
      })

    }
  }

  final case class ReadNEmitN(n: Int) extends GraphStage[FlowShape[Int, Int]] {
    override val shape = FlowShape(Inlet[Int]("readN.in"), Outlet[Int]("readN.out"))

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(shape.in, EagerTerminateInput)
        setHandler(shape.out, EagerTerminateOutput)
        override def preStart(): Unit = readN(shape.in, n)(e ⇒ emitMultiple(shape.out, e.iterator, () ⇒ completeStage()), (_) ⇒ ())
      }
  }

  final case class ReadNEmitRestOnComplete(n: Int) extends GraphStage[FlowShape[Int, Int]] {
    override val shape = FlowShape(Inlet[Int]("readN.in"), Outlet[Int]("readN.out"))

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) {
        setHandler(shape.in, EagerTerminateInput)
        setHandler(shape.out, EagerTerminateOutput)
        override def preStart(): Unit =
          readN(shape.in, n)(
            _ ⇒ failStage(new IllegalStateException("Shouldn't happen!")),
            e ⇒ emitMultiple(shape.out, e.iterator, () ⇒ completeStage()))
      }
  }

  "A GraphStageLogic" must {

    "read N and emit N before completing" in assertAllStagesStopped {
      Source(1 to 10).via(ReadNEmitN(2)).runWith(TestSink.probe)
        .request(10)
        .expectNext(1, 2)
        .expectComplete()
    }

    "read N should not emit if upstream completes before N is sent" in assertAllStagesStopped {
      Source(1 to 5).via(ReadNEmitN(6)).runWith(TestSink.probe)
        .request(10)
        .expectComplete()
    }

    "read N should not emit if upstream fails before N is sent" in assertAllStagesStopped {
      val error = new IllegalArgumentException("Don't argue like that!")
      Source(1 to 5).map(x ⇒ if (x > 3) throw error else x).via(ReadNEmitN(6)).runWith(TestSink.probe)
        .request(10)
        .expectError(error)
    }

    "read N should provide elements read if onComplete happens before N elements have been seen" in assertAllStagesStopped {
      Source(1 to 5).via(ReadNEmitRestOnComplete(6)).runWith(TestSink.probe)
        .request(10)
        .expectNext(1, 2, 3, 4, 5)
        .expectComplete()
    }

    "emit all things before completing" in assertAllStagesStopped {

      Source.empty.via(emit1234.named("testStage")).runWith(TestSink.probe)
        .request(5)
        .expectNext(1, 2, 3, 4)
        .expectComplete()

    }

    "emit all things before completing with two fused stages" in assertAllStagesStopped {
      val g = aggressive(Flow[Int].via(emit1234).via(emit5678))

      Source.empty.via(g).runWith(TestSink.probe)
        .request(9)
        .expectNextN(1 to 8)
        .expectComplete()
    }

    "emit all things before completing with three fused stages" in assertAllStagesStopped {
      val g = aggressive(Flow[Int].via(emit1234).via(passThrough).via(emit5678))

      Source.empty.via(g).runWith(TestSink.probe)
        .request(9)
        .expectNextN(1 to 8)
        .expectComplete()
    }

    "emit properly after empty iterable" in assertAllStagesStopped {

      Source.fromGraph(emitEmptyIterable).runWith(Sink.seq).futureValue should ===(List(42))

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

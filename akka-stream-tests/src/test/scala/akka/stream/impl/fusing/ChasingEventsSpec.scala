/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.fusing

import akka.stream.scaladsl.{ Sink, Source }
import akka.stream._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.AkkaSpec

class ChasingEventsSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withFuzzing(false))

  class CancelInChasedPull extends GraphStage[FlowShape[Int, Int]] {
    val in = Inlet[Int]("Propagate.in")
    val out = Outlet[Int]("Propagate.out")
    override val shape: FlowShape[Int, Int] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private var first = true
        override def onPush(): Unit = push(out, grab(in))
        override def onPull(): Unit = {
          pull(in)
          if (!first) cancel(in)
          first = false
        }

        setHandlers(in, out, this)
      }
  }

  class CompleteInChasedPush extends GraphStage[FlowShape[Int, Int]] {
    val in = Inlet[Int]("Propagate.in")
    val out = Outlet[Int]("Propagate.out")
    override val shape: FlowShape[Int, Int] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private var first = true
        override def onPush(): Unit = {
          push(out, grab(in))
          complete(out)
        }
        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
  }

  class FailureInChasedPush extends GraphStage[FlowShape[Int, Int]] {
    val in = Inlet[Int]("Propagate.in")
    val out = Outlet[Int]("Propagate.out")
    override val shape: FlowShape[Int, Int] = FlowShape(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private var first = true
        override def onPush(): Unit = {
          push(out, grab(in))
          fail(out, TE("test failure"))
        }
        override def onPull(): Unit = pull(in)

        setHandlers(in, out, this)
      }
  }

  class ChasableSink extends GraphStage[SinkShape[Int]] {
    val in = Inlet[Int]("Chaseable.in")
    override val shape: SinkShape[Int] = SinkShape(in)

    @throws(classOf[Exception])
    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler {
        override def preStart(): Unit = pull(in)
        override def onPush(): Unit = pull(in)
        setHandler(in, this)
      }
  }

  "Event chasing" must {

    "propagate cancel if enqueued immediately after pull" in {
      val upstream = TestPublisher.probe[Int]()

      Source.fromPublisher(upstream).via(new CancelInChasedPull).runWith(Sink.ignore)

      upstream.sendNext(0)
      upstream.expectCancellation()

    }

    "propagate complete if enqueued immediately after push" in {
      val downstream = TestSubscriber.probe[Int]()

      Source(1 to 10).via(new CompleteInChasedPush).runWith(Sink.fromSubscriber(downstream))

      downstream.requestNext(1)
      downstream.expectComplete()

    }

    "propagate failure if enqueued immediately after push" in {
      val downstream = TestSubscriber.probe[Int]()

      Source(1 to 10).via(new FailureInChasedPush).runWith(Sink.fromSubscriber(downstream))

      downstream.requestNext(1)
      downstream.expectError()

    }

  }

}

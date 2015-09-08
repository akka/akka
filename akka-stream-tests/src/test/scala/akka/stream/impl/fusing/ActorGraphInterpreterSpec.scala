/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import java.util.concurrent.TimeoutException

import akka.actor.{ ActorSystem, Cancellable, Scheduler }
import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.testkit.{ TestPublisher, AkkaSpec, TestSubscriber }

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.testkit.Utils._

class ActorGraphInterpreterSpec extends AkkaSpec {
  implicit val mat = ActorMaterializer()

  "ActorGraphInterpreter" must {

    "be able to interpret a simple identity graph stage" in assertAllStagesStopped {
      val identity = new GraphStages.Identity[Int]

      Await.result(
        Source(1 to 100).via(identity).grouped(200).runWith(Sink.head),
        3.seconds) should ===(1 to 100)

    }

    "be able to interpret a simple bidi stage" in assertAllStagesStopped {
      val identityBidi = new GraphStage[BidiShape[Int, Int, Int, Int]] {
        val in1 = Inlet[Int]("in1")
        val in2 = Inlet[Int]("in2")
        val out1 = Outlet[Int]("out1")
        val out2 = Outlet[Int]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def createLogic: GraphStageLogic = new GraphStageLogic {
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

      val identity = BidiFlow.wrap(identityBidi).join(Flow[Int].map { x ⇒ x })

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

        override def createLogic: GraphStageLogic = new GraphStageLogic {
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

      val (f1, f2) = FlowGraph.closed(takeAll, takeAll)(Keep.both) { implicit b ⇒
        (out1, out2) ⇒
          import FlowGraph.Implicits._
          val bidi = b.add(rotatedBidi)

          Source(1 to 10) ~> bidi.in1
          out2 <~ bidi.out2

          bidi.in2 <~ Source(1 to 100)
          bidi.out1 ~> out1
      }.run()

      Await.result(f1, 3.seconds) should ===(1 to 100)
      Await.result(f2, 3.seconds) should ===(1 to 10)
    }

    "be able to implement a timeout bidiStage" in {
      class IdleTimeout[I, O](
        val system: ActorSystem,
        val timeout: FiniteDuration) extends GraphStage[BidiShape[I, I, O, O]] {
        val in1 = Inlet[I]("in1")
        val in2 = Inlet[O]("in2")
        val out1 = Outlet[I]("out1")
        val out2 = Outlet[O]("out2")
        val shape = BidiShape(in1, out1, in2, out2)

        override def toString = "IdleTimeout"

        override def createLogic: GraphStageLogic = new GraphStageLogic {
          private var timerCancellable: Option[Cancellable] = None
          private var nextDeadline: Deadline = Deadline.now + timeout

          setHandler(in1, new InHandler {
            override def onPush(): Unit = {
              onActivity()
              push(out1, grab(in1))
            }
            override def onUpstreamFinish(): Unit = complete(out1)
          })

          setHandler(in2, new InHandler {
            override def onPush(): Unit = {
              onActivity()
              push(out2, grab(in2))
            }
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

          private def onActivity(): Unit = nextDeadline = Deadline.now + timeout

          private def onTimerTick(): Unit =
            if (nextDeadline.isOverdue())
              failStage(new TimeoutException(s"No reads or writes happened in $timeout."))

          override def preStart(): Unit = {
            super.preStart()
            val checkPeriod = timeout / 8
            val callback = getAsyncCallback[Unit]((_) ⇒ onTimerTick())
            import system.dispatcher
            timerCancellable = Some(system.scheduler.schedule(timeout, checkPeriod)(callback.invoke(())))
          }

          override def postStop(): Unit = {
            super.postStop()
            timerCancellable.foreach(_.cancel())
          }
        }
      }

      val upWrite = TestPublisher.probe[String]()
      val upRead = TestSubscriber.probe[Int]()

      val downWrite = TestPublisher.probe[Int]()
      val downRead = TestSubscriber.probe[String]()

      FlowGraph.closed() { implicit b ⇒
        import FlowGraph.Implicits._
        val timeoutStage = b.add(new IdleTimeout[String, Int](system, 2.seconds))
        Source(upWrite) ~> timeoutStage.in1; timeoutStage.out1 ~> Sink(downRead)
        Sink(upRead) <~ timeoutStage.out2; timeoutStage.in2 <~ Source(downWrite)
      }.run()

      // Request enough for the whole test
      upRead.request(100)
      downRead.request(100)

      upWrite.sendNext("DATA1")
      downRead.expectNext("DATA1")
      Thread.sleep(1500)

      downWrite.sendNext(1)
      upRead.expectNext(1)
      Thread.sleep(1500)

      upWrite.sendNext("DATA2")
      downRead.expectNext("DATA2")
      Thread.sleep(1000)

      downWrite.sendNext(2)
      upRead.expectNext(2)

      upRead.expectNoMsg(500.millis)
      val error1 = upRead.expectError()
      val error2 = downRead.expectError()

      error1.isInstanceOf[TimeoutException] should be(true)
      error1.getMessage should be("No reads or writes happened in 2 seconds.")
      error2 should ===(error1)

      upWrite.expectCancellation()
      downWrite.expectCancellation()
    }

  }

}

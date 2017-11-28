/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.Done
import akka.actor.ActorRef
import akka.stream._
import akka.stream.scaladsl.{ Flow, Keep, Sink, Source }
import akka.stream.stage._
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.testkit.TestProbe

import scala.concurrent.duration._
import scala.language.reflectiveCalls

class AsyncCallbackCancellationSpec extends StreamSpec {

  class Stage[T](
    callbackProbe:     ActorRef,
    earlyCallbacks:    List[AnyRef] = Nil,
    earlyCancellation: Boolean      = false)
    extends GraphStageWithMaterializedValue[FlowShape[T, T], AsyncCallback[AnyRef]] {

    val in = Inlet[T]("in")
    val out = Outlet[T]("out")
    val shape = FlowShape(in, out)

    def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, AsyncCallback[AnyRef]) = {
      val logic = new GraphStageLogic(shape) {
        val cb = getAsyncCallback[AnyRef](a ⇒ callbackProbe ! a)
        earlyCallbacks.foreach(cb.invoke)
        if (earlyCancellation) cb.cancel()

        setHandlers(in, out, new InHandler with OutHandler {
          def onPush(): Unit = push(out, grab(in))
          def onPull(): Unit = pull(in)
        })

      }
      (logic, logic.cb)
    }
  }

  implicit val mat = ActorMaterializer()

  "Async Callbacks" should {

    "not allow invokes after cancelling" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val cb =
        Source.fromPublisher(in)
          .viaMat(Flow.fromGraph(new Stage(probe.ref)))(Keep.right)
          .to(Sink.ignore)
          .run()

      val future1 = cb.invokeWithFeedback("hepp!")
      probe.expectMsg("hepp!")
      future1.futureValue should ===(Done)

      cb.cancel()
      val future2 = cb.invokeWithFeedback("tjo!")
      cb.invoke("hey!")
      future2.failed.futureValue shouldBe a[StreamDetachedException]

      // and neither came through
      probe.expectNoMessage(500.millis)
      in.sendComplete()
    }

    "pass through early callbacks" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val cb =
        Source.fromPublisher(in)
          .viaMat(Flow.fromGraph(new Stage(probe.ref, earlyCallbacks = List("hepp!", "heya!"))))(Keep.right)
          .to(Sink.ignore)
          .run()

      probe.expectMsg("hepp!")
      probe.expectMsg("heya!")

      // and neither came through
      probe.expectNoMessage(500.millis)
      in.sendComplete()
    }

    "not pass through early callbacks if there is an earlier cancel" in {
      val probe = TestProbe()
      // this should put the early callbacks in pending, and then cancel while they still are pending
      val in = TestPublisher.probe[Int]()
      val cb =
        Source.fromPublisher(in)
          .viaMat(Flow.fromGraph(new Stage(
            probe.ref,
            earlyCallbacks = List("hepp!", "heya!"),
            earlyCancellation = true
          )))(Keep.right)
          .to(Sink.ignore)
          .run()

      probe.expectNoMessage(500.millis)
      in.sendComplete()
    }

    "not cause any trouble with multiple cancels" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val cb =
        Source.fromPublisher(in)
          .viaMat(Flow.fromGraph(new Stage(probe.ref)))(Keep.right)
          .to(Sink.ignore)
          .run()

      cb.cancel()
      cb.cancel()

      in.sendComplete()
    }

  }

}

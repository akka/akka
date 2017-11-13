/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.Done
import akka.actor.ActorRef
import akka.stream.stage._
import akka.stream._
import akka.stream.scaladsl.{ Keep, Sink, Source }
import akka.stream.testkit.{ TestPublisher, TestSubscriber }
import akka.testkit.{ AkkaSpec, TestProbe }

import scala.concurrent.{ Future, Promise }

class AsyncCallbackSpec extends AkkaSpec {

  implicit val materializer = ActorMaterializer(ActorMaterializerSettings(system).withFuzzing(false))

  case object Started
  case class Elem(n: Int)
  case object Stopped

  class AsyncCallbackGraphStage(probe: ActorRef, early: Option[AsyncCallback[AnyRef] ⇒ Unit] = None)
    extends GraphStageWithMaterializedValue[FlowShape[Int, Int], Future[AsyncCallback[AnyRef]]] {

    val in = Inlet[Int]("in")
    val out = Outlet[Int]("out")
    val shape = FlowShape(in, out)

    def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[AsyncCallback[AnyRef]]) = {
      val promise = Promise[AsyncCallback[AnyRef]]()

      val logic = new GraphStageLogic(shape) {
        val callback = getAsyncCallback((whatever: AnyRef) ⇒ {
          whatever match {
            case t: Throwable ⇒ throw t
            case anythingElse ⇒ probe ! anythingElse
          }
        })
        early.foreach(cb ⇒ cb(callback))
        promise.success(callback)

        override def preStart(): Unit = {
          probe ! Started
        }

        override def postStop(): Unit = {
          probe ! Stopped
        }

        setHandlers(in, out, new InHandler with OutHandler {
          def onPush(): Unit = {
            val n = grab(in)
            probe ! Elem(n)
            push(out, n)
          }

          def onPull(): Unit = {
            pull(in)
          }
        })
      }

      (logic, promise.future)
    }
  }

  "The support for async callbacks" must {

    "invoke without feedback, happy path" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val out = TestSubscriber.probe[Int]()
      val callbackF = Source.fromPublisher(in)
        .viaMat(new AsyncCallbackGraphStage(probe.ref))(Keep.right)
        .to(Sink.fromSubscriber(out))
        .run()

      probe.expectMsg(Started)
      out.request(1)
      in.expectRequest()

      val callback = callbackF.futureValue
      (0 to 10).foreach { n ⇒
        val msg = "whatever" + n
        callback.invoke(msg)
        probe.expectMsg(msg)
      }

      in.sendComplete()
      out.expectComplete()

      probe.expectMsg(Stopped)
    }

    "invoke with feedback, happy path" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val out = TestSubscriber.probe[Int]()
      val callbackF = Source.fromPublisher(in)
        .viaMat(new AsyncCallbackGraphStage(probe.ref))(Keep.right)
        .to(Sink.fromSubscriber(out))
        .run()

      probe.expectMsg(Started)
      out.request(1)
      in.expectRequest()

      val callback = callbackF.futureValue
      (0 to 10).foreach { n ⇒
        val msg = "whatever" + n
        val feedbackF = callback.invokeWithFeedback(msg)
        probe.expectMsg(msg)
        feedbackF.futureValue should ===(Done)
      }
      in.sendComplete()
      out.expectComplete()

      probe.expectMsg(Stopped)
    }

    "fail the feedback future if stage is stopped" in {
      val probe = TestProbe()
      val callbackF = Source.empty
        .viaMat(new AsyncCallbackGraphStage(probe.ref))(Keep.right)
        .to(Sink.ignore)
        .run()

      probe.expectMsg(Started)
      probe.expectMsg(Stopped)

      val feedbakF = callbackF.futureValue.invokeWithFeedback("whatever")
      feedbakF.failed.futureValue shouldBe a[StreamDetachedException]
    }

    "invoke early" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val callbackF = Source.fromPublisher(in)
        .viaMat(new AsyncCallbackGraphStage(
          probe.ref,
          Some(asyncCb ⇒ asyncCb.invoke("early"))
        ))(Keep.right)
        .to(Sink.ignore)
        .run()

      // and deliver in order
      val callback = callbackF.futureValue
      callback.invoke("later")

      probe.expectMsg(Started)
      probe.expectMsg("early")
      probe.expectMsg("later")

      in.sendComplete()
      probe.expectMsg(Stopped)

    }

    "invoke with feedback early" in {
      val probe = TestProbe()
      val earlyFeedback = Promise[Done]()
      val in = TestPublisher.probe[Int]()
      val callbackF = Source.fromPublisher(in)
        .viaMat(new AsyncCallbackGraphStage(
          probe.ref,
          Some(asyncCb ⇒ earlyFeedback.completeWith(asyncCb.invokeWithFeedback("early")))
        ))(Keep.right)
        .to(Sink.ignore)
        .run()

      // and deliver in order
      val callback = callbackF.futureValue
      val laterFeedbackF = callback.invokeWithFeedback("later")

      probe.expectMsg(Started)
      probe.expectMsg("early")
      earlyFeedback.future.futureValue should ===(Done)

      probe.expectMsg("later")
      laterFeedbackF.futureValue should ===(Done)

      in.sendComplete()
      probe.expectMsg(Stopped)
    }

    "accept concurrent input" in {
      val probe = TestProbe()
      val in = TestPublisher.probe[Int]()
      val callbackF = Source.fromPublisher(in)
        .viaMat(new AsyncCallbackGraphStage(probe.ref))(Keep.right)
        .to(Sink.ignore)
        .run()

      val callback = callbackF.futureValue

      import system.dispatcher
      val feedbacks = (1 to 100).map { n ⇒
        Future {
          callback.invokeWithFeedback(n.toString)
        }.flatMap(d ⇒ d)
      }

      probe.expectMsg(Started)
      Future.sequence(feedbacks).futureValue should have size (100)
      (1 to 100).map(_ ⇒ probe.expectMsgType[String]).toSet should have size (100)

      in.sendComplete()
      probe.expectMsg(Stopped)
    }

    "fail the feedback if the invoke fails" in {
      val probe = TestProbe()
      val callbackF = Source.empty
        .viaMat(new AsyncCallbackGraphStage(probe.ref))(Keep.right)
        .to(Sink.ignore)
        .run()

      probe.expectMsg(Started)
      probe.expectMsg(Stopped)

      val feedbakF = callbackF.futureValue.invokeWithFeedback(new RuntimeException("oh my gosh, whale of a wash!"))
      feedbakF.failed.futureValue shouldBe a[RuntimeException]
    }

  }
}

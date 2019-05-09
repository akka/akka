/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import scala.concurrent.duration._
import akka.actor.{ Actor, ActorRef, Props, Status }
import akka.stream.ActorMaterializer
import akka.stream.Attributes.inputBuffer
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._
import akka.testkit.TestProbe

import scala.concurrent.Promise

object ActorRefBackpressureSinkSpec {
  val initMessage = "start"
  val completeMessage = "done"
  val ackMessage = "ack"

  class Fw(ref: ActorRef) extends Actor {
    def receive = {
      case `initMessage` =>
        sender() ! ackMessage
        ref.forward(initMessage)
      case `completeMessage` =>
        ref.forward(completeMessage)
      case msg: Int =>
        sender() ! ackMessage
        ref.forward(msg)
    }
  }

  case object TriggerAckMessage

  class Fw2(ref: ActorRef) extends Actor {
    var actorRef: ActorRef = Actor.noSender

    def receive = {
      case TriggerAckMessage =>
        actorRef ! ackMessage
      case msg =>
        actorRef = sender()
        ref.forward(msg)
    }
  }

}

class ActorRefBackpressureSinkSpec extends StreamSpec {
  import ActorRefBackpressureSinkSpec._
  implicit val mat = ActorMaterializer()

  def createActor[T](c: Class[T]) =
    system.actorOf(Props(c, testActor).withDispatcher("akka.test.stream-dispatcher"))

  "An ActorRefBackpressureSink" must {

    "send the elements to the ActorRef" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw])
      Source(List(1, 2, 3)).runWith(Sink.actorRefWithAck(fw, initMessage, ackMessage, completeMessage))
      expectMsg("start")
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg(completeMessage)
    }

    "send the elements to the ActorRef2" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw])
      val probe = TestSource.probe[Int].to(Sink.actorRefWithAck(fw, initMessage, ackMessage, completeMessage)).run()
      probe.sendNext(1)
      expectMsg("start")
      expectMsg(1)
      probe.sendNext(2)
      expectMsg(2)
      probe.sendNext(3)
      expectMsg(3)
      probe.sendComplete()
      expectMsg(completeMessage)
    }

    "cancel stream when actor terminates" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw])
      val publisher =
        TestSource.probe[Int].to(Sink.actorRefWithAck(fw, initMessage, ackMessage, completeMessage)).run().sendNext(1)
      expectMsg(initMessage)
      expectMsg(1)
      system.stop(fw)
      publisher.expectCancellation()
    }

    "send message only when backpressure received" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw2])
      val publisher = TestSource.probe[Int].to(Sink.actorRefWithAck(fw, initMessage, ackMessage, completeMessage)).run()
      expectMsg(initMessage)

      publisher.sendNext(1)
      expectNoMsg(200.millis)
      fw ! TriggerAckMessage
      expectMsg(1)

      publisher.sendNext(2)
      publisher.sendNext(3)
      publisher.sendComplete()
      fw ! TriggerAckMessage
      expectMsg(2)
      fw ! TriggerAckMessage
      expectMsg(3)

      expectMsg(completeMessage)
    }

    "keep on sending even after the buffer has been full" in assertAllStagesStopped {
      val bufferSize = 16
      val streamElementCount = bufferSize + 4
      val fw = createActor(classOf[Fw2])
      val sink = Sink
        .actorRefWithAck(fw, initMessage, ackMessage, completeMessage)
        .withAttributes(inputBuffer(bufferSize, bufferSize))
      val bufferFullProbe = Promise[akka.Done.type]
      Source(1 to streamElementCount)
        .alsoTo(Flow[Int].drop(bufferSize - 1).to(Sink.foreach(_ => bufferFullProbe.trySuccess(akka.Done))))
        .to(sink)
        .run()
      bufferFullProbe.future.futureValue should ===(akka.Done)
      expectMsg(initMessage)
      fw ! TriggerAckMessage
      for (i <- 1 to streamElementCount) {
        expectMsg(i)
        fw ! TriggerAckMessage
      }
      expectMsg(completeMessage)
    }

    "work with one element buffer" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw2])
      val publisher =
        TestSource
          .probe[Int]
          .to(Sink.actorRefWithAck(fw, initMessage, ackMessage, completeMessage).withAttributes(inputBuffer(1, 1)))
          .run()

      expectMsg(initMessage)
      fw ! TriggerAckMessage

      publisher.sendNext(1)
      expectMsg(1)

      fw ! TriggerAckMessage
      expectNoMsg(200.millis) // Ack received but buffer empty

      publisher.sendNext(2) // Buffer this value
      fw ! TriggerAckMessage
      expectMsg(2)

      publisher.sendComplete()
      expectMsg(completeMessage)
    }

    "fail to materialize with zero sized input buffer" in {
      val fw = createActor(classOf[Fw])
      an[IllegalArgumentException] shouldBe thrownBy {
        val badSink =
          Sink.actorRefWithAck(fw, initMessage, ackMessage, completeMessage).withAttributes(inputBuffer(0, 0))
        Source.single(()).runWith(badSink)
      }
    }

    "signal failure on abrupt termination" in {
      val mat = ActorMaterializer()
      val probe = TestProbe()

      val sink = Sink
        .actorRefWithAck[String](probe.ref, initMessage, ackMessage, completeMessage)
        .withAttributes(inputBuffer(1, 1))

      val maybe = Source.maybe[String].to(sink).run()(mat)

      probe.expectMsg(initMessage)
      mat.shutdown()
      probe.expectMsgType[Status.Failure]
    }

  }

}

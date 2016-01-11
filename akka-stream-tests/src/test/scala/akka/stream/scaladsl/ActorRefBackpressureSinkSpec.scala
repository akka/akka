/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.{ Actor, ActorRef, Props }
import akka.stream.ActorMaterializer
import akka.stream.testkit.Utils._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl._

object ActorRefBackpressureSinkSpec {
  val initMessage = "start"
  val completeMessage = "done"
  val ackMessage = "ack"

  class Fw(ref: ActorRef) extends Actor {
    def receive = {
      case `initMessage` ⇒
        sender() ! ackMessage
        ref forward initMessage
      case `completeMessage` ⇒
        ref forward completeMessage
      case msg: Int ⇒
        sender() ! ackMessage
        ref forward msg
    }
  }

  case object TriggerAckMessage

  class Fw2(ref: ActorRef) extends Actor {
    var actorRef: ActorRef = Actor.noSender

    def receive = {
      case TriggerAckMessage ⇒
        actorRef ! ackMessage
      case msg ⇒
        actorRef = sender()
        ref forward msg
    }
  }

}

class ActorRefBackpressureSinkSpec extends AkkaSpec {
  import ActorRefBackpressureSinkSpec._
  implicit val mat = ActorMaterializer()

  def createActor[T](c: Class[T]) =
    system.actorOf(Props(c, testActor).withDispatcher("akka.test.stream-dispatcher"))

  "An ActorRefBackpressureSink" must {

    "send the elements to the ActorRef" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw])
      Source(List(1, 2, 3)).runWith(Sink.actorRefWithAck(fw,
        initMessage, ackMessage, completeMessage))
      expectMsg("start")
      expectMsg(1)
      expectMsg(2)
      expectMsg(3)
      expectMsg(completeMessage)
    }

    "send the elements to the ActorRef2" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw])
      val probe = TestSource.probe[Int].to(Sink.actorRefWithAck(fw,
        initMessage, ackMessage, completeMessage)).run()
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
      val publisher = TestSource.probe[Int].to(Sink.actorRefWithAck(fw,
        initMessage, ackMessage, completeMessage)).run().sendNext(1)
      expectMsg(initMessage)
      expectMsg(1)
      system.stop(fw)
      publisher.expectCancellation()
    }

    "send message only when backpressure received" in assertAllStagesStopped {
      val fw = createActor(classOf[Fw2])
      val publisher = TestSource.probe[Int].to(Sink.actorRefWithAck(fw,
        initMessage, ackMessage, completeMessage)).run()
      expectMsg(initMessage)

      publisher.sendNext(1)
      expectNoMsg()
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

  }

}

package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.scalatest.junit.JUnitSuite
import org.junit.Test


class ForwardActorSpec extends JUnitSuite {

  object ForwardState {
    var sender: Actor = null
    val finished = new CountDownLatch(1)
  }

  class ReceiverActor extends Actor {
    def receive = {
      case "SendBang" => {
        ForwardState.sender = sender.get
        ForwardState.finished.countDown
      }
      case "SendBangBang" => reply("SendBangBang")
    }
  }


  class ForwardActor extends Actor {
    val receiverActor = new ReceiverActor
    receiverActor.start
    def receive = {
      case "SendBang" => receiverActor.forward("SendBang")
      case "SendBangBang" => receiverActor.forward("SendBangBang")
    }
  }

  class BangSenderActor extends Actor {
    val forwardActor = new ForwardActor
    forwardActor.start
    forwardActor ! "SendBang"
    def receive = {
      case _ => {}
    }
  }

  class BangBangSenderActor extends Actor {
    val forwardActor = new ForwardActor
    forwardActor.start
    (forwardActor !! "SendBangBang") match {
      case Some(_) => {ForwardState.finished.countDown}
      case None => {}
    }
    def receive = {
      case _ => {}
    }
  }

  @Test
  def shouldForwardActorReferenceWhenInvokingForwardOnBang = {
    val senderActor = new BangSenderActor
    senderActor.start
    assert(ForwardState.finished.await(2, TimeUnit.SECONDS))
    assert(ForwardState.sender ne null)
    assert(senderActor === ForwardState.sender)
  }

  @Test
  def shouldForwardActorReferenceWhenInvokingForwardOnBangBang = {
    val senderActor = new BangBangSenderActor
    senderActor.start
    assert(ForwardState.finished.await(2, TimeUnit.SECONDS))
  }
}

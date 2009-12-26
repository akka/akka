package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test


class ForwardActorTest extends JUnitSuite {

  object ForwardState {
    var sender: Actor = null
  }

  class ReceiverActor extends Actor {
    def receive = {
      case "Send" => ForwardState.sender = sender.get
    }
  }

  class ForwardActor extends Actor {
    val receiverActor = new ReceiverActor
    receiverActor.start
    def receive = {
      case "Send" => receiverActor.forward("Send")
    }
  }

  class SenderActor extends Actor {
    val forwardActor = new ForwardActor
    forwardActor.start
    forwardActor ! "Send"
    def receive = {
      case _ => {}
    }
  }

  @Test
  def shouldForwardActorReferenceWhenInvokingForward = {
    val senderActor = new SenderActor
    senderActor.start
    Thread.sleep(1000)
    assert(ForwardState.sender != null)
    assert(senderActor === ForwardState.sender)
  }
}

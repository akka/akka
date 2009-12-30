package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test


class ForwardActorTest extends JUnitSuite {

  object ForwardState {
    var sender: Actor = null
    var result: String = "nada"
  }

  class ReceiverActor extends Actor {
    def receive = {
      case "SendBang" => ForwardState.sender = sender.get
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
    ForwardState.result = (forwardActor !! "SendBangBang").getOrElse("nada")
    def receive = {
      case _ => {}
    }
  }

  @Test
  def shouldForwardActorReferenceWhenInvokingForwardOnBang = {
    val senderActor = new BangSenderActor
    senderActor.start
    Thread.sleep(1000)
    assert(ForwardState.sender ne null)
    assert(senderActor === ForwardState.sender)
  }

  @Test
  def shouldForwardActorReferenceWhenInvokingForwardOnBangBang = {
    val senderActor = new BangBangSenderActor
    senderActor.start
    Thread.sleep(1000)
    assert(ForwardState.result === "SendBangBang")
  }
}

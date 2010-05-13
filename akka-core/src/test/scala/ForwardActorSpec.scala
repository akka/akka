package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import Actor._

object ForwardActorSpec {
  object ForwardState {
    var sender: Option[ActorRef] = None
  }

  class ReceiverActor extends Actor {
    val latch = new CountDownLatch(1)
    def receive = {
      case "SendBang" => {
        ForwardState.sender = Some(self.replyTo.get.left.get)
        latch.countDown
      }
      case "SendBangBang" => reply("SendBangBang")
    }
  }


  class ForwardActor extends Actor {
    val receiverActor = newActor[ReceiverActor]
    receiverActor.start
    def receive = {
      case "SendBang" => receiverActor.forward("SendBang")
      case "SendBangBang" => receiverActor.forward("SendBangBang")
    }
  }

  class BangSenderActor extends Actor {
    val forwardActor = newActor[ForwardActor]
    forwardActor.start
    forwardActor ! "SendBang"
    def receive = {
      case _ => {}
    }
  }

  class BangBangSenderActor extends Actor {
    val latch = new CountDownLatch(1)
    val forwardActor = newActor[ForwardActor]
    forwardActor.start
    (forwardActor !! "SendBangBang") match {
      case Some(_) => latch.countDown
      case None => {}
    }
    def receive = {
      case _ => {}
    }
  }  
}

class ForwardActorSpec extends JUnitSuite {
  import ForwardActorSpec._
  
  @Test
  def shouldForwardActorReferenceWhenInvokingForwardOnBang {
    val senderActor = newActor[BangSenderActor]
    val latch = senderActor.actor.asInstanceOf[BangSenderActor]
      .forwardActor.actor.asInstanceOf[ForwardActor]
      .receiverActor.actor.asInstanceOf[ReceiverActor]
      .latch
    senderActor.start
    assert(latch.await(1L, TimeUnit.SECONDS))
    assert(ForwardState.sender ne null)
    assert(senderActor.toString === ForwardState.sender.get.toString)
  }

  @Test
  def shouldForwardActorReferenceWhenInvokingForwardOnBangBang {
    val senderActor = newActor[BangBangSenderActor]
    senderActor.start
    val latch = senderActor.actor.asInstanceOf[BangBangSenderActor].latch
    assert(latch.await(1L, TimeUnit.SECONDS)) 
  }
}

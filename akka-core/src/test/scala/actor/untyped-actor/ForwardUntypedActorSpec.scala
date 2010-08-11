package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import UntypedActor._

object ForwardUntypedActorSpec {
  object ForwardState {
    var sender: Option[UntypedActorRef] = None
  }

  class ReceiverUntypedActor extends UntypedActor {
    //println(getClass + ":" + toString + " => " + getContext)
    val latch = new CountDownLatch(1)
    def onReceive(message: Any){
      println(getClass.getName + " got " + message)
      message match {
        case "SendBang" => {
          ForwardState.sender = getContext.getSender
          latch.countDown
        }
        case "SendBangBang" => getContext.replyUnsafe("SendBangBang")
        case x => throw new IllegalArgumentException("Unknown message: " + x);
      }
    }
  }

  class ForwardUntypedActor extends UntypedActor {
    val receiverActor = actorOf(classOf[ReceiverUntypedActor]).start
    def onReceive(message: Any){ 
      message match {
        case "SendBang" => receiverActor.forward("SendBang",getContext)
        case "SendBangBang" => receiverActor.forward("SendBangBang",getContext)
      }
    }
  }

  class BangSenderUntypedActor extends UntypedActor {
    val forwardActor = actorOf(classOf[ForwardUntypedActor]).start
    forwardActor.sendOneWay("SendBang",getContext)
    def onReceive(message: Any) = ()
  }

  class BangBangSenderUntypedActor extends UntypedActor {
    val latch: CountDownLatch = new CountDownLatch(1)   
    val forwardActor = actorOf(classOf[ForwardUntypedActor]).start
    (forwardActor sendRequestReply "SendBangBang") match {
      case _ => latch.countDown
    }
    def onReceive(message: Any) = ()
  }
}

class ForwardUntypedActorSpec extends JUnitSuite {
  import ForwardUntypedActorSpec._

  @Test
  def shouldForwardUntypedActorReferenceWhenInvokingForwardOnBang {
    val senderActor = actorOf(classOf[BangSenderUntypedActor])
    val latch = senderActor.actorRef.actor.asInstanceOf[BangSenderUntypedActor]
      .forwardActor.actorRef.actor.asInstanceOf[ForwardUntypedActor]
      .receiverActor.actorRef.actor.asInstanceOf[ReceiverUntypedActor]
      .latch

    senderActor.start
    assert(latch.await(5L, TimeUnit.SECONDS))
    println(senderActor.actorRef.toString + " " + ForwardState.sender.get.actorRef.toString)
    assert(ForwardState.sender ne null)
    assert(senderActor.actorRef.toString === ForwardState.sender.get.actorRef.toString)
  }

  @Test
  def shouldForwardUntypedActorReferenceWhenInvokingForwardOnBangBang {
    val senderActor = actorOf(classOf[BangBangSenderUntypedActor]).start
    val latch = senderActor.actorRef.actor.asInstanceOf[BangBangSenderUntypedActor].latch
    assert(latch.await(1L, TimeUnit.SECONDS))
  }
}

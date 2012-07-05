/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.duration._

import Actor._

object ForwardActorSpec {
  object ForwardState {
    var sender: Option[ActorRef] = None
  }

  class ReceiverActor extends Actor {
    val latch = TestLatch()
    def receive = {
      case "SendBang" ⇒ {
        ForwardState.sender = self.sender
        latch.countDown()
      }
      case "SendBangBang" ⇒ self.reply("SendBangBang")
    }
  }

  class ForwardActor extends Actor {
    val receiverActor = actorOf[ReceiverActor]
    receiverActor.start()
    def receive = {
      case "SendBang"     ⇒ receiverActor.forward("SendBang")
      case "SendBangBang" ⇒ receiverActor.forward("SendBangBang")
    }
  }

  class BangSenderActor extends Actor {
    val forwardActor = actorOf[ForwardActor]
    forwardActor.start()
    forwardActor ! "SendBang"
    def receive = {
      case _ ⇒ {}
    }
  }

  class BangBangSenderActor extends Actor {
    val latch = TestLatch()
    val forwardActor = actorOf[ForwardActor]
    forwardActor.start()
    (forwardActor !! "SendBangBang") match {
      case Some(_) ⇒ latch.countDown()
      case None    ⇒ {}
    }
    def receive = {
      case _ ⇒ {}
    }
  }
}

class ForwardActorSpec extends WordSpec with MustMatchers {
  import ForwardActorSpec._

  "A Forward Actor" must {
    "forward actor reference when invoking forward on bang" in {
      val senderActor = actorOf[BangSenderActor]
      val latch = senderActor.actor.asInstanceOf[BangSenderActor]
        .forwardActor.actor.asInstanceOf[ForwardActor]
        .receiverActor.actor.asInstanceOf[ReceiverActor]
        .latch
      senderActor.start()
      latch.await
      ForwardState.sender must not be (null)
      senderActor.toString must be(ForwardState.sender.get.toString)
    }

    "forward actor reference when invoking forward on bang bang" in {
      val senderActor = actorOf[BangBangSenderActor]
      senderActor.start()
      val latch = senderActor.actor.asInstanceOf[BangBangSenderActor].latch
      latch.await
    }
  }
}

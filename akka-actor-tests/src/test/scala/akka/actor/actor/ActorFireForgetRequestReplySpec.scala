/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.testkit._
import akka.testkit.Testing.sleepFor
import akka.util.duration._

import Actor._
import akka.config.Supervision._
import akka.dispatch.Dispatchers

object ActorFireForgetRequestReplySpec {

  class ReplyActor extends Actor {
    def receive = {
      case "Send" ⇒
        self.reply("Reply")
      case "SendImplicit" ⇒
        self.channel ! "ReplyImplicit"
    }
  }

  class CrashingTemporaryActor extends Actor {
    self.lifeCycle = Temporary

    def receive = {
      case "Die" ⇒
        state.finished.await
        throw new Exception("Expected exception")
    }
  }

  class SenderActor(replyActor: ActorRef) extends Actor {
    def receive = {
      case "Init" ⇒
        replyActor ! "Send"
      case "Reply" ⇒ {
        state.s = "Reply"
        state.finished.await
      }
      case "InitImplicit" ⇒ replyActor ! "SendImplicit"
      case "ReplyImplicit" ⇒ {
        state.s = "ReplyImplicit"
        state.finished.await
      }
    }
  }

  object state {
    var s = "NIL"
    val finished = TestBarrier(2)
  }
}

class ActorFireForgetRequestReplySpec extends WordSpec with MustMatchers with BeforeAndAfterEach {
  import ActorFireForgetRequestReplySpec._

  override def beforeEach() = {
    state.finished.reset
  }

  "An Actor" must {

    "reply to bang message using reply" in {
      val replyActor = actorOf[ReplyActor].start()
      val senderActor = actorOf(new SenderActor(replyActor)).start()
      senderActor ! "Init"
      state.finished.await
      state.s must be("Reply")
    }

    "reply to bang message using implicit sender" in {
      val replyActor = actorOf[ReplyActor].start()
      val senderActor = actorOf(new SenderActor(replyActor)).start()
      senderActor ! "InitImplicit"
      state.finished.await
      state.s must be("ReplyImplicit")
    }

    "should shutdown crashed temporary actor" in {
      val actor = actorOf[CrashingTemporaryActor].start()
      actor.isRunning must be(true)
      actor ! "Die"
      state.finished.await
      sleepFor(1 second)
      actor.isShutdown must be(true)
    }
  }
}

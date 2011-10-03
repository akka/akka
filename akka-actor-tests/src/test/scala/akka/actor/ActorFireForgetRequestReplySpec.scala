/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.BeforeAndAfterEach

import akka.testkit._
import akka.testkit.Testing.sleepFor
import akka.util.duration._

import akka.actor.Actor._
import akka.dispatch.Dispatchers

object ActorFireForgetRequestReplySpec {

  class ReplyActor extends Actor {
    def receive = {
      case "Send" ⇒
        reply("Reply")
      case "SendImplicit" ⇒
        channel ! "ReplyImplicit"
    }
  }

  class CrashingActor extends Actor {
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
      val replyActor = actorOf[ReplyActor]
      val senderActor = actorOf(new SenderActor(replyActor))
      senderActor ! "Init"
      state.finished.await
      state.s must be("Reply")
    }

    "reply to bang message using implicit sender" in {
      val replyActor = actorOf[ReplyActor]
      val senderActor = actorOf(new SenderActor(replyActor))
      senderActor ! "InitImplicit"
      state.finished.await
      state.s must be("ReplyImplicit")
    }

    "should shutdown crashed temporary actor" in {
      filterEvents(EventFilter[Exception]("Expected")) {
        val supervisor = Supervisor(OneForOneStrategy(List(classOf[Exception]), Some(0)))
        val actor = actorOf(Props[CrashingActor].withSupervisor(supervisor))
        actor.isShutdown must be(false)
        actor ! "Die"
        state.finished.await
        sleepFor(1 second)
        actor.isShutdown must be(true)
        supervisor.stop()
      }
    }
  }
}

package se.scalablesolutions.akka.actor

import java.util.concurrent.{TimeUnit, CountDownLatch}
import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers
import Actor._

class ActorFireForgetRequestReplySpec extends JUnitSuite {

  object state {
    var s = "NIL"
    val finished = new CountDownLatch(1)
  }

  class ReplyActor extends Actor {
    dispatcher = Dispatchers.newThreadBasedDispatcher(this)

    def receive = {
      case "Send" => reply("Reply")
      case "SendImplicit" => replyTo.get.left.get ! "ReplyImplicit"
    }
  }

  class SenderActor(replyActor: ActorID) extends Actor {
    dispatcher = Dispatchers.newThreadBasedDispatcher(this)

    def receive = {
      case "Init" => replyActor ! "Send"
      case "Reply" => {
        state.s = "Reply"
        state.finished.countDown
      }
      case "InitImplicit" => replyActor ! "SendImplicit"
      case "ReplyImplicit" => {
        state.s = "ReplyImplicit"
        state.finished.countDown
      }
    }
  }

  @Test
  def shouldReplyToBangMessageUsingReply = {
    val replyActor = newActor[ReplyActor]
    replyActor.start
    val senderActor = newActor(() => new SenderActor(replyActor))
    senderActor.start
    senderActor ! "Init"
    assert(state.finished.await(1, TimeUnit.SECONDS))
    assert("Reply" === state.s)
  }

  @Test
  def shouldReplyToBangMessageUsingImplicitSender = {
    val replyActor = newActor[ReplyActor]
    replyActor.start
    val senderActor = newActor(() => new SenderActor(replyActor))
    senderActor.start
    senderActor ! "InitImplicit"
    assert(state.finished.await(1, TimeUnit.SECONDS))
    assert("ReplyImplicit" === state.s)
  }
}

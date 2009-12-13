package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

import se.scalablesolutions.akka.dispatch.Dispatchers

object state {
  var s = "NIL"
}

class ReplyActor extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  def receive = {
    case "Send" => reply("Reply")
    case "SendImplicit" => sender.get ! "ReplyImplicit"
  }
}

class SenderActor(replyActor: Actor) extends Actor {
  dispatcher = Dispatchers.newThreadBasedDispatcher(this)

  def receive = {
    case "Init" => replyActor ! "Send"
    case "Reply" => state.s = "Reply"
    case "InitImplicit" => replyActor ! "SendImplicit"
    case "ReplyImplicit" => state.s = "ReplyImplicit"
  }
}

class ActorFireForgetRequestReplyTest extends JUnitSuite {

  @Test
  def shouldReplyToBangMessageUsingReply = {
    import Actor.Sender.Self  

    val replyActor = new ReplyActor
    replyActor.start
    val senderActor = new SenderActor(replyActor)
    senderActor.start
    senderActor ! "Init"
    Thread.sleep(1000)
    assert("Reply" === state.s)
  }

  @Test
  def shouldReplyToBangMessageUsingImplicitSender = {
    import Actor.Sender.Self

    val replyActor = new ReplyActor
    replyActor.start
    val senderActor = new SenderActor(replyActor)
    senderActor.start
    senderActor ! "InitImplicit"
    Thread.sleep(1000)
    assert("ReplyImplicit" === state.s)
  }
}

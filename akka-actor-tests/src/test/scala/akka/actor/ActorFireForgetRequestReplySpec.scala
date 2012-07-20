/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import org.scalatest.BeforeAndAfterEach
import scala.concurrent.util.duration._
import scala.concurrent.Await
import akka.pattern.ask

object ActorFireForgetRequestReplySpec {

  class ReplyActor extends Actor {
    def receive = {
      case "Send" ⇒
        sender ! "Reply"
      case "SendImplicit" ⇒
        sender ! "ReplyImplicit"
    }
  }

  class CrashingActor extends Actor {
    import context.system
    def receive = {
      case "Die" ⇒
        state.finished.await
        throw new Exception("Expected exception")
    }
  }

  class SenderActor(replyActor: ActorRef) extends Actor {
    import context.system
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorFireForgetRequestReplySpec extends AkkaSpec with BeforeAndAfterEach with DefaultTimeout {
  import ActorFireForgetRequestReplySpec._

  override def beforeEach() = {
    state.finished.reset
  }

  "An Actor" must {

    "reply to bang message using reply" in {
      val replyActor = system.actorOf(Props[ReplyActor])
      val senderActor = system.actorOf(Props(new SenderActor(replyActor)))
      senderActor ! "Init"
      state.finished.await
      state.s must be("Reply")
    }

    "reply to bang message using implicit sender" in {
      val replyActor = system.actorOf(Props[ReplyActor])
      val senderActor = system.actorOf(Props(new SenderActor(replyActor)))
      senderActor ! "InitImplicit"
      state.finished.await
      state.s must be("ReplyImplicit")
    }

    "should shutdown crashed temporary actor" in {
      filterEvents(EventFilter[Exception]("Expected exception")) {
        val supervisor = system.actorOf(Props(new Supervisor(
          OneForOneStrategy(maxNrOfRetries = 0)(List(classOf[Exception])))))
        val actor = Await.result((supervisor ? Props[CrashingActor]).mapTo[ActorRef], timeout.duration)
        actor.isTerminated must be(false)
        actor ! "Die"
        state.finished.await
        Thread.sleep(1.second.dilated.toMillis)
        actor.isTerminated must be(true)
        system.stop(supervisor)
      }
    }
  }
}

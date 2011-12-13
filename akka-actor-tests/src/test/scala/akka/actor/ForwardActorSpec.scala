/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import akka.util.duration._
import Actor._
import akka.util.Duration
import akka.dispatch.Await

object ForwardActorSpec {
  val ExpectedMessage = "FOO"

  def createForwardingChain(system: ActorSystem): ActorRef = {
    val replier = system.actorOf(new Actor {
      def receive = { case x ⇒ sender ! x }
    })

    def mkforwarder(forwardTo: ActorRef) = system.actorOf(
      new Actor {
        def receive = { case x ⇒ forwardTo forward x }
      })

    mkforwarder(mkforwarder(mkforwarder(replier)))
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ForwardActorSpec extends AkkaSpec {
  import ForwardActorSpec._

  "A Forward Actor" must {

    "forward actor reference when invoking forward on tell" in {
      val latch = new TestLatch(1)

      val replyTo = system.actorOf(new Actor { def receive = { case ExpectedMessage ⇒ testActor ! ExpectedMessage } })

      val chain = createForwardingChain(system)

      chain.tell(ExpectedMessage, replyTo)
      expectMsg(5 seconds, ExpectedMessage)
    }

    "forward actor reference when invoking forward on ask" in {
      val chain = createForwardingChain(system)
      chain.ask(ExpectedMessage, 5000) onSuccess { case ExpectedMessage ⇒ testActor ! ExpectedMessage }
      expectMsg(5 seconds, ExpectedMessage)
    }
  }
}

/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import akka.util.duration._
import Actor._
import akka.util.Duration
import akka.AkkaApplication

object ForwardActorSpec {
  val ExpectedMessage = "FOO"

  def createForwardingChain(app: AkkaApplication): ActorRef = {
    val replier = app.actorOf(new Actor {
      def receive = { case x ⇒ channel ! x }
    })

    def mkforwarder(forwardTo: ActorRef) = app.actorOf(
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

    "forward actor reference when invoking forward on bang" in {
      val latch = new TestLatch(1)

      val replyTo = actorOf(new Actor { def receive = { case ExpectedMessage ⇒ latch.countDown() } })

      val chain = createForwardingChain(app)

      chain.tell(ExpectedMessage, replyTo)
      latch.await(Duration(5, "s")) must be === true
    }

    "forward actor reference when invoking forward on bang bang" in {
      val chain = createForwardingChain(app)
      chain.ask(ExpectedMessage, 5000).get must be === ExpectedMessage
    }
  }
}

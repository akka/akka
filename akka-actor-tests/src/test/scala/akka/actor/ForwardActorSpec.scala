/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.util.duration._

import Actor._
import akka.util.Duration

object ForwardActorSpec {
  val ExpectedMessage = "FOO"

  def createForwardingChain(): ActorRef = {
    val replier = actorOf(new Actor {
      def receive = { case x ⇒ self reply x }
    })

    def mkforwarder(forwardTo: ActorRef) = actorOf(
      new Actor {
        def receive = { case x ⇒ forwardTo forward x }
      })

    mkforwarder(mkforwarder(mkforwarder(replier)))
  }
}

class ForwardActorSpec extends WordSpec with MustMatchers {
  import ForwardActorSpec._

  "A Forward Actor" must {

    "forward actor reference when invoking forward on bang" in {
      val latch = new TestLatch(1)

      val replyTo = actorOf(new Actor { def receive = { case ExpectedMessage ⇒ latch.countDown() } })

      val chain = createForwardingChain()

      chain.tell(ExpectedMessage, replyTo)
      latch.await(Duration(5, "s")) must be === true
    }

    "forward actor reference when invoking forward on bang bang" in {
      val chain = createForwardingChain()
      chain.ask(ExpectedMessage, 5000).get must be === ExpectedMessage
    }
  }
}

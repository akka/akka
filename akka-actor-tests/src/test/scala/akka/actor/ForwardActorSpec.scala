/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit._
import scala.concurrent.duration._
import akka.pattern.{ ask, pipe }

object ForwardActorSpec {
  val ExpectedMessage = "FOO"

  def createForwardingChain(system: ActorSystem): ActorRef = {
    val replier = system.actorOf(Props(new Actor {
      def receive = { case x ⇒ sender() ! x }
    }))

    def mkforwarder(forwardTo: ActorRef) = system.actorOf(Props(
      new Actor {
        def receive = { case x ⇒ forwardTo forward x }
      }))

    mkforwarder(mkforwarder(mkforwarder(replier)))
  }
}

class ForwardActorSpec extends AkkaSpec {
  import ForwardActorSpec._
  implicit val ec = system.dispatcher
  "A Forward Actor" must {

    "forward actor reference when invoking forward on tell" in {
      val replyTo = system.actorOf(Props(new Actor { def receive = { case ExpectedMessage ⇒ testActor ! ExpectedMessage } }))

      val chain = createForwardingChain(system)

      chain.tell(ExpectedMessage, replyTo)
      expectMsg(5 seconds, ExpectedMessage)
    }

    "forward actor reference when invoking forward on ask" in {
      val chain = createForwardingChain(system)
      chain.ask(ExpectedMessage)(5 seconds) pipeTo testActor
      expectMsg(5 seconds, ExpectedMessage)
    }
  }
}

/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import scala.concurrent.duration._
import akka.testkit.EventFilter

object FunctionRefSpec {

  case class GetForwarder(replyTo: ActorRef)
  case class DropForwarder(ref: FunctionRef)
  case class Forwarded(msg: Any, sender: ActorRef)

  class Super extends Actor {
    def receive = {
      case GetForwarder(replyTo) ⇒
        val cell = context.asInstanceOf[ActorCell]
        val ref = cell.addFunctionRef((sender, msg) ⇒ replyTo ! Forwarded(msg, sender))
        replyTo ! ref
      case DropForwarder(ref) ⇒
        val cell = context.asInstanceOf[ActorCell]
        cell.removeFunctionRef(ref)
    }
  }

  class SupSuper extends Actor {
    val s = context.actorOf(Props[Super], "super")
    def receive = {
      case msg ⇒ s ! msg
    }
  }

}

class FunctionRefSpec extends AkkaSpec with ImplicitSender {
  import FunctionRefSpec._

  def commonTests(s: ActorRef) = {
    s ! GetForwarder(testActor)
    val forwarder = expectMsgType[FunctionRef]

    "forward messages" in {
      forwarder ! "hello"
      expectMsg(Forwarded("hello", testActor))
    }

    "be watchable" in {
      s ! GetForwarder(testActor)
      val f = expectMsgType[FunctionRef]
      watch(f)
      s ! DropForwarder(f)
      expectTerminated(f)
    }

    "be able to watch" in {
      s ! GetForwarder(testActor)
      val f = expectMsgType[FunctionRef]
      forwarder.watch(f)
      s ! DropForwarder(f)
      expectMsg(Forwarded(Terminated(f)(true, false), null))
    }

    "terminate when their parent terminates" in {
      watch(forwarder)
      s ! PoisonPill
      expectTerminated(forwarder)
    }
  }

  "A FunctionRef" when {

    "created by a toplevel actor" must {
      val s = system.actorOf(Props[Super], "super")
      commonTests(s)
    }

    "created by a non-toplevel actor" must {
      val s = system.actorOf(Props[SupSuper], "supsuper")
      commonTests(s)
    }

    "not registered" must {
      "not be found" in {
        val provider = system.asInstanceOf[ExtendedActorSystem].provider
        val ref = new FunctionRef(testActor.path / "blabla", provider, system.eventStream, (x, y) ⇒ ())
        EventFilter[ClassCastException](occurrences = 1) intercept {
          // needs to be something that fails when the deserialized form is not a FunctionRef
          // this relies upon serialize-messages during tests
          testActor ! DropForwarder(ref)
          expectNoMsg(1.second)
        }
      }
    }

  }
}

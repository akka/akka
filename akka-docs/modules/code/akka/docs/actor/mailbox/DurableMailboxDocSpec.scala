package akka.docs.actor.mailbox

//#imports
import akka.actor.Actor
import akka.actor.Props
import akka.actor.mailbox.FileDurableMailboxType

//#imports

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec

class MyActor extends Actor {
  def receive = {
    case x ⇒
  }
}

class DurableMailboxDocSpec extends AkkaSpec {

  "define dispatcher with durable mailbox" in {
    //#define-dispatcher
    val dispatcher = system.dispatcherFactory.newDispatcher(
      "my-dispatcher", throughput = 1, mailboxType = FileDurableMailboxType).build
    val myActor = system.actorOf(Props[MyActor].withDispatcher(dispatcher), name = "myactor")
    //#define-dispatcher
    myActor ! "hello"
  }

}

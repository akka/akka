/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.PoisonPill
import akka.actor.Props
import akka.dispatch.Await
import akka.testkit.AkkaSpec
import akka.testkit.TestLatch
import akka.util.duration._

object DurableMailboxSpecActorFactory {

  class MailboxTestActor extends Actor {
    def receive = { case "sum" ⇒ sender ! "sum" }
  }

  class Sender(latch: TestLatch) extends Actor {
    def receive = { case "sum" ⇒ latch.countDown() }
  }

}

/**
 * Subclass must define dispatcher in the supplied config for the specific backend.
 * The id of the dispatcher must be the same as the `<backendName>-dispatcher`.
 */
abstract class DurableMailboxSpec(val backendName: String, config: String) extends AkkaSpec(config) {
  import DurableMailboxSpecActorFactory._

  def createMailboxTestActor(id: String): ActorRef =
    system.actorOf(Props(new MailboxTestActor).withDispatcher(backendName + "-dispatcher"))

  "A " + backendName + " based mailbox backed actor" must {

    "handle reply to ! for 1 message" in {
      val latch = new TestLatch(1)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = system.actorOf(Props(new Sender(latch)))

      queueActor.!("sum")(sender)
      Await.ready(latch, 10 seconds)
      queueActor ! PoisonPill
      sender ! PoisonPill
    }

    "handle reply to ! for multiple messages" in {
      val latch = new TestLatch(5)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = system.actorOf(Props(new Sender(latch)))

      for (i ← 1 to 10) queueActor.!("sum")(sender)

      Await.ready(latch, 10 seconds)
      queueActor ! PoisonPill
      sender ! PoisonPill
    }
  }

}

package akka.actor.mailbox

import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }
import akka.actor._
import akka.actor.Actor._
import akka.dispatch.MessageDispatcher
import akka.dispatch.Dispatchers
import akka.dispatch.MailboxType
import akka.testkit.AkkaSpec

object DurableMailboxSpecActorFactory {

  class MailboxTestActor extends Actor {
    def receive = { case "sum" ⇒ sender ! "sum" }
  }

  class Sender(latch: CountDownLatch) extends Actor {
    def receive = { case "sum" ⇒ latch.countDown() }
  }

}

/**
 * Subclass must define dispatcher in the supplied config for the specific backend.
 * The id of the dispatcher must be the same as the `<backendName>-dispatcher`.
 */
abstract class DurableMailboxSpec(val backendName: String, config: String) extends AkkaSpec(config) with BeforeAndAfterEach {
  import DurableMailboxSpecActorFactory._

  def createMailboxTestActor(id: String): ActorRef =
    system.actorOf(Props(new MailboxTestActor).withDispatcher(backendName + "-dispatcher"))

  "A " + backendName + " based mailbox backed actor" must {

    "handle reply to ! for 1 message" in {
      val latch = new CountDownLatch(1)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = system.actorOf(Props(new Sender(latch)))

      queueActor.!("sum")(sender)
      latch.await(10, TimeUnit.SECONDS) must be(true)
      queueActor ! PoisonPill
      sender ! PoisonPill
    }

    // FIXME ignored due to zookeeper issue, ticket #1423
    "handle reply to ! for multiple messages" ignore {
      val latch = new CountDownLatch(5)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = system.actorOf(Props(new Sender(latch)))

      for (i ← 1 to 10) queueActor.!("sum")(sender)

      latch.await(10, TimeUnit.SECONDS) must be(true)
      queueActor ! PoisonPill
      sender ! PoisonPill
    }
  }

}

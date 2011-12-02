package akka.actor.mailbox

import java.util.concurrent.TimeUnit
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }
import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.CountDownLatch
import akka.dispatch.MessageDispatcher
import akka.testkit.AkkaSpec
import akka.dispatch.Dispatchers

object DurableMailboxSpecActorFactory {

  class MailboxTestActor extends Actor {
    def receive = { case "sum" ⇒ sender ! "sum" }
  }

  class Sender(latch: CountDownLatch) extends Actor {
    def receive = { case "sum" ⇒ latch.countDown() }
  }

}

abstract class DurableMailboxSpec(val backendName: String, val mailboxType: DurableMailboxType) extends AkkaSpec with BeforeAndAfterEach {
  import DurableMailboxSpecActorFactory._

  implicit val dispatcher = system.dispatcherFactory.newDispatcher(backendName, throughput = 1, mailboxType = mailboxType).build

  def createMailboxTestActor(id: String)(implicit dispatcher: MessageDispatcher): ActorRef =
    actorOf(Props(new MailboxTestActor).withDispatcher(dispatcher))

  "A " + backendName + " based mailbox backed actor" must {

    "handle reply to ! for 1 message" in {
      val latch = new CountDownLatch(1)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = actorOf(Props(new Sender(latch)))

      queueActor.!("sum")(sender)
      latch.await(10, TimeUnit.SECONDS) must be(true)
      queueActor ! PoisonPill
      sender ! PoisonPill
    }

    // FIXME ignored due to zookeeper issue, ticket #1423
    "handle reply to ! for multiple messages" ignore {
      val latch = new CountDownLatch(5)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = actorOf(Props(new Sender(latch)))

      for (i ← 1 to 10) queueActor.!("sum")(sender)

      latch.await(10, TimeUnit.SECONDS) must be(true)
      queueActor ! PoisonPill
      sender ! PoisonPill
    }
  }

}

package akka.actor.mailbox

import java.util.concurrent.TimeUnit

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterEach, BeforeAndAfterAll }

import akka.actor._
import akka.actor.Actor._
import java.util.concurrent.CountDownLatch
import akka.dispatch.MessageDispatcher

object DurableMailboxSpecActorFactory {

  class MailboxTestActor extends Actor {
    def receive = { case "sum" ⇒ reply("sum") }
  }

  def createMailboxTestActor(id: String)(implicit dispatcher: MessageDispatcher): ActorRef =
    actorOf(Props[MailboxTestActor].withDispatcher(dispatcher).withLifeCycle(Temporary))
}

abstract class DurableMailboxSpec(val backendName: String, val storage: DurableMailboxStorage) extends WordSpec with MustMatchers with BeforeAndAfterEach with BeforeAndAfterAll {
  import DurableMailboxSpecActorFactory._

  implicit val dispatcher = DurableDispatcher(backendName, storage, 1)

  "A " + backendName + " based mailbox backed actor" should {

    "should handle reply to ! for 1 message" in {
      val latch = new CountDownLatch(1)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = new LocalActorRef(Props(self ⇒ { case "sum" ⇒ latch.countDown }), Props.randomAddress, true)

      queueActor.!("sum")(Some(sender))
      latch.await(10, TimeUnit.SECONDS) must be(true)
    }

    "should handle reply to ! for multiple messages" in {
      val latch = new CountDownLatch(5)
      val queueActor = createMailboxTestActor(backendName + " should handle reply to !")
      val sender = new LocalActorRef(Props(self ⇒ { case "sum" ⇒ latch.countDown }), Props.randomAddress, true)

      for (i ← 1 to 5) queueActor.!("sum")(Some(sender))

      latch.await(10, TimeUnit.SECONDS) must be(true)
    }
  }

  override def beforeEach() {
    registry.local.shutdownAll
  }
}

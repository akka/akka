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
import java.io.InputStream
import scala.annotation.tailrec
import com.typesafe.config.Config

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

  protected def streamMustContain(in: InputStream, words: String): Unit = {
    val output = new Array[Byte](8192)

    def now = System.currentTimeMillis

    def string(len: Int) = new String(output, 0, len, "ISO-8859-1") // don’t want parse errors

    @tailrec def read(end: Int = 0, start: Long = now): Int =
      in.read(output, end, output.length - end) match {
        case -1 ⇒ end
        case x ⇒
          val next = end + x
          if (string(next).contains(words) || now - start > 10000 || next == output.length) next
          else read(next, start)
      }

    val result = string(read())
    if (!result.contains(words)) throw new Exception("stream did not contain '" + words + "':\n" + result)
  }

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

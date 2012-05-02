/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import akka.testkit.AkkaSpec
import akka.testkit.TestLatch
import akka.util.duration._
import java.io.InputStream
import scala.annotation.tailrec
import com.typesafe.config.Config
import akka.actor._
import akka.dispatch.{ Mailbox, Await }

object DurableMailboxSpecActorFactory {

  class MailboxTestActor extends Actor {
    def receive = { case x ⇒ sender ! x }
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

  private val props = Props[MailboxTestActor].withDispatcher(backendName + "-dispatcher")

  def createMailboxTestActor(id: String = ""): ActorRef = id match {
    case null | "" ⇒ system.actorOf(props)
    case some      ⇒ system.actorOf(props, some)
  }

  def isDurableMailbox(m: Mailbox): Boolean

  "A " + backendName + " based mailbox backed actor" must {

    "get a new, unique, durable mailbox" in {
      val a1, a2 = createMailboxTestActor()
      isDurableMailbox(a1.asInstanceOf[LocalActorRef].underlying.mailbox) must be(true)
      isDurableMailbox(a2.asInstanceOf[LocalActorRef].underlying.mailbox) must be(true)
      (a1.asInstanceOf[LocalActorRef].underlying.mailbox ne a2.asInstanceOf[LocalActorRef].underlying.mailbox) must be(true)
    }

    "deliver messages at most once" in {
      val queueActor = createMailboxTestActor()
      implicit val sender = testActor

      val msgs = 1 to 100 map { x ⇒ "foo" + x }

      msgs foreach { m ⇒ queueActor ! m }

      msgs foreach { m ⇒ expectMsg(m) }

      expectNoMsg()
    }
  }

}

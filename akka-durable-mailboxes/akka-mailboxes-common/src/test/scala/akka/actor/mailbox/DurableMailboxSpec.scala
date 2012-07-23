/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.mailbox

import language.postfixOps

import java.io.InputStream
import java.util.concurrent.TimeoutException

import scala.annotation.tailrec

import org.scalatest.{ WordSpec, BeforeAndAfterAll }
import org.scalatest.matchers.MustMatchers

import com.typesafe.config.{ ConfigFactory, Config }

import DurableMailboxSpecActorFactory.{ MailboxTestActor, AccumulatorActor }
import akka.actor.{ RepointableRef, Props, ActorSystem, ActorRefWithCell, ActorRef, ActorCell, Actor }
import akka.dispatch.Mailbox
import akka.testkit.TestKit
import scala.concurrent.util.duration.intToDurationInt

object DurableMailboxSpecActorFactory {

  class MailboxTestActor extends Actor {
    def receive = { case x ⇒ sender ! x }
  }

  class AccumulatorActor extends Actor {
    var num = 0l
    def receive = {
      case x: Int ⇒ num += x
      case "sum"  ⇒ sender ! num
    }
  }

}

object DurableMailboxSpec {
  def fallbackConfig: Config = ConfigFactory.parseString("""
      akka {
        event-handlers = ["akka.testkit.TestEventListener"]
        loglevel = "WARNING"
        stdout-loglevel = "WARNING"
      }
      """)
}

/**
 * Reusable test fixture for durable mailboxes. Implements a few basic tests. More
 * tests can be added in concrete subclass.
 *
 * Subclass must define dispatcher in the supplied config for the specific backend.
 * The id of the dispatcher must be the same as the `<backendName>-dispatcher`.
 */
abstract class DurableMailboxSpec(system: ActorSystem, val backendName: String)
  extends TestKit(system) with WordSpec with MustMatchers with BeforeAndAfterAll {

  import DurableMailboxSpecActorFactory._

  /**
   * Subclass must define dispatcher in the supplied config for the specific backend.
   * The id of the dispatcher must be the same as the `<backendName>-dispatcher`.
   */
  def this(backendName: String, config: String) = {
    this(ActorSystem(backendName + "BasedDurableMailboxSpec",
      ConfigFactory.parseString(config).withFallback(DurableMailboxSpec.fallbackConfig)),
      backendName)
  }

  final override def beforeAll {
    atStartup()
  }

  /**
   * May be implemented in concrete subclass to do additional things once before test
   * cases are run.
   */
  protected def atStartup() {}

  final override def afterAll {
    system.shutdown()
    try system.awaitTermination(5 seconds) catch {
      case _: TimeoutException ⇒ system.log.warning("Failed to stop [{}] within 5 seconds", system.name)
    }
    atTermination()
  }

  /**
   * May be implemented in concrete subclass to do additional things once after all
   * test cases have been run.
   */
  def atTermination() {}

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

  def createMailboxTestActor(props: Props = Props[MailboxTestActor], id: String = ""): ActorRef = {
    val ref = id match {
      case null | "" ⇒ system.actorOf(props.withDispatcher(backendName + "-dispatcher"))
      case some      ⇒ system.actorOf(props.withDispatcher(backendName + "-dispatcher"), some)
    }
    awaitCond(ref match {
      case r: RepointableRef ⇒ r.isStarted
    }, 1 second, 10 millis)
    ref
  }

  private def isDurableMailbox(m: Mailbox): Boolean =
    m.messageQueue.isInstanceOf[DurableMessageQueue]

  "A " + backendName + " based mailbox backed actor" must {

    "get a new, unique, durable mailbox" in {
      val a1, a2 = createMailboxTestActor()
      val mb1 = a1.asInstanceOf[ActorRefWithCell].underlying.asInstanceOf[ActorCell].mailbox
      val mb2 = a2.asInstanceOf[ActorRefWithCell].underlying.asInstanceOf[ActorCell].mailbox
      isDurableMailbox(mb1) must be(true)
      isDurableMailbox(mb2) must be(true)
      (mb1 ne mb2) must be(true)
    }

    "deliver messages at most once" in {
      val queueActor = createMailboxTestActor()
      implicit val sender = testActor

      val msgs = 1 to 100 map { x ⇒ "foo" + x }

      msgs foreach { m ⇒ queueActor ! m }

      msgs foreach { m ⇒ expectMsg(m) }

      expectNoMsg()
    }

    "support having multiple actors at the same time" in {
      val actors = Vector.fill(3)(createMailboxTestActor(Props[AccumulatorActor]))

      actors foreach { a ⇒ isDurableMailbox(a.asInstanceOf[ActorRefWithCell].underlying.asInstanceOf[ActorCell].mailbox) must be(true) }

      val msgs = 1 to 3

      val expectedResult: Long = msgs.sum

      for (a ← actors; m ← msgs) a ! m

      for (a ← actors) {
        implicit val sender = testActor
        a ! "sum"
        expectMsg(expectedResult)
      }

      expectNoMsg()
    }
  }

}

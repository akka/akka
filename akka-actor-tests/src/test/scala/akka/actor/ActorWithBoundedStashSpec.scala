/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import akka.dispatch.BoundedDequeBasedMailbox
import akka.pattern.ask
import scala.concurrent.Await
import scala.concurrent.util.duration._
import akka.actor.ActorSystem.Settings
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.Assertions.intercept
import org.scalatest.BeforeAndAfterEach

object ActorWithBoundedStashSpec {

  class StashingActor extends Actor with Stash {
    def receive = {
      case msg: String if msg.startsWith("hello") ⇒
        stash()
        sender ! "ok"

      case "world" ⇒
        context.become(afterWorldBehaviour)
        unstashAll()

    }

    def afterWorldBehaviour: Receive = {
      case _ ⇒ stash()
    }
  }

  class StashingActorWithOverflow extends Actor with Stash {
    var numStashed = 0

    def receive = {
      case msg: String if msg.startsWith("hello") ⇒
        numStashed += 1
        try { stash(); sender ! "ok" } catch {
          case _: StashOverflowException ⇒
            if (numStashed == 21) {
              sender ! "STASHOVERFLOW"
              context stop self
            } else {
              sender ! "Unexpected StashOverflowException: " + numStashed
            }
        }
    }
  }

  // bounded deque-based mailbox with capacity 10
  class Bounded10(settings: Settings, config: Config) extends BoundedDequeBasedMailbox(10, 500 millis)

  class Bounded100(settings: Settings, config: Config) extends BoundedDequeBasedMailbox(100, 500 millis)

  val dispatcherId1 = "my-dispatcher-1"
  val dispatcherId2 = "my-dispatcher-2"

  val testConf: Config = ConfigFactory.parseString("""
    %s {
      mailbox-type = "%s"
      stash-capacity = 20
    }
    %s {
      mailbox-type = "%s"
      stash-capacity = 20
    }
    """.format(dispatcherId1, classOf[Bounded10].getName, dispatcherId2, classOf[Bounded100].getName))
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorWithBoundedStashSpec extends AkkaSpec(ActorWithBoundedStashSpec.testConf) with BeforeAndAfterEach with DefaultTimeout with ImplicitSender {
  import ActorWithBoundedStashSpec._

  override def atStartup: Unit = {
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*hello.*")))
  }

  override def beforeEach(): Unit =
    system.eventStream.subscribe(testActor, classOf[DeadLetter])

  override def afterEach(): Unit =
    system.eventStream.unsubscribe(testActor, classOf[DeadLetter])

  "An Actor with Stash" must {

    "end up in DeadLetters in case of a capacity violation" in {
      val stasher = system.actorOf(Props[StashingActor].withDispatcher(dispatcherId1))
      // fill up stash
      for (n ← 1 to 11) {
        stasher ! "hello" + n
        expectMsg("ok")
      }

      // cause unstashAll with capacity violation
      stasher ! "world"
      expectMsg(DeadLetter("hello1", testActor, stasher))

      stasher ! PoisonPill
      // stashed messages are sent to deadletters when stasher is stopped
      for (n ← 2 to 11) expectMsg(DeadLetter("hello" + n, testActor, stasher))
    }

    "throw a StashOverflowException in case of a stash capacity violation" in {
      val stasher = system.actorOf(Props[StashingActorWithOverflow].withDispatcher(dispatcherId2))
      // fill up stash
      for (n ← 1 to 20) {
        stasher ! "hello" + n
        expectMsg("ok")
      }

      stasher ! "hello21"
      expectMsg("STASHOVERFLOW")

      // stashed messages are sent to deadletters when stasher is stopped,
      for (n ← 1 to 20) expectMsg(DeadLetter("hello" + n, testActor, stasher))
    }
  }
}

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
      case "hello1" ⇒ stash()
      case "world"  ⇒ unstashAll()
    }
  }

  class StashingActorWithOverflow extends Actor with Stash {
    var numStashed = 0

    def receive = {
      case "hello2" ⇒
        numStashed += 1
        try stash() catch {
          case _: StashOverflowException ⇒
            if (numStashed == 21) {
              sender ! "STASHOVERFLOW"
              context stop self
            }
        }
    }
  }

  // bounded deque-based mailbox with capacity 10
  class Bounded(settings: Settings, config: Config) extends BoundedDequeBasedMailbox(10, 10 millis)

  val dispatcherId = "my-dispatcher"

  val testConf: Config = ConfigFactory.parseString("""
    %s {
      mailbox-type = "%s"
      stash-capacity = 20
    }
    """.format(dispatcherId, classOf[Bounded].getName))
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorWithBoundedStashSpec extends AkkaSpec(ActorWithBoundedStashSpec.testConf) with BeforeAndAfterEach with DefaultTimeout with ImplicitSender {
  import ActorWithBoundedStashSpec._

  override def atStartup: Unit = {
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*hello1")))
    system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*hello2")))
  }

  override def beforeEach(): Unit =
    system.eventStream.subscribe(testActor, classOf[DeadLetter])

  override def afterEach(): Unit =
    system.eventStream.unsubscribe(testActor, classOf[DeadLetter])

  "An Actor with Stash" must {

    "end up in DeadLetters in case of a capacity violation" in {
      val stasher = system.actorOf(Props[StashingActor].withDispatcher(dispatcherId))
      // fill up stash
      (1 to 11) foreach { _ ⇒ stasher ! "hello1" }

      // cause unstashAll with capacity violation
      stasher ! "world"
      expectMsg(DeadLetter("hello1", testActor, stasher))
      system stop stasher
      (1 to 10) foreach { _ ⇒ expectMsg(DeadLetter("hello1", testActor, stasher)) }
    }

    "throw a StashOverflowException in case of a stash capacity violation" in {
      val stasher = system.actorOf(Props[StashingActorWithOverflow].withDispatcher(dispatcherId))
      // fill up stash
      (1 to 21) foreach { _ ⇒ stasher ! "hello2" }
      expectMsg("STASHOVERFLOW")
      (1 to 20) foreach { _ ⇒ expectMsg(DeadLetter("hello2", testActor, stasher)) }
    }
  }
}

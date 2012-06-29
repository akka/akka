/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import language.postfixOps

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import akka.dispatch.{ Await, BoundedDequeBasedMailbox }
import akka.pattern.ask
import scala.concurrent.util.duration._
import akka.actor.ActorSystem.Settings
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach

object ActorWithBoundedStashSpec {

  class StashingActor(implicit sys: ActorSystem) extends Actor with Stash {
    def receive = {
      case "hello" ⇒ stash()
      case "world" ⇒ unstashAll()
    }
  }

  class StashingActorWithOverflow(implicit sys: ActorSystem) extends Actor with Stash {
    var numStashed = 0

    def receive = {
      case "hello" ⇒
        numStashed += 1
        try stash() catch { case e: StashOverflowException ⇒ if (numStashed == 21) sender ! "STASHOVERFLOW" }
    }
  }

  val testConf: Config = ConfigFactory.parseString("""
    my-dispatcher {
      mailbox-type = "akka.actor.ActorWithBoundedStashSpec$Bounded"
      stash-capacity = 20
    }
    """)

  // bounded deque-based mailbox with capacity 10
  class Bounded(settings: Settings, config: Config) extends BoundedDequeBasedMailbox(10, 1 seconds)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorWithBoundedStashSpec extends AkkaSpec(ActorWithBoundedStashSpec.testConf) with DefaultTimeout with BeforeAndAfterEach with ImplicitSender {
  import ActorWithBoundedStashSpec._

  implicit val sys = system

  override def atStartup { system.eventStream.publish(Mute(EventFilter[Exception]("Crashing..."))) }

  def myProps(creator: ⇒ Actor): Props = Props(creator).withDispatcher("my-dispatcher")

  "An Actor with Stash and BoundedDequeBasedMailbox" must {

    "end up in DeadLetters in case of a capacity violation" in {
      system.eventStream.subscribe(testActor, classOf[DeadLetter])

      val stasher = system.actorOf(myProps(new StashingActor))
      // fill up stash
      (1 to 11) foreach { _ ⇒ stasher ! "hello" }

      // cause unstashAll with capacity violation
      stasher ! "world"
      expectMsg(DeadLetter("hello", testActor, stasher))
      system.eventStream.unsubscribe(testActor, classOf[DeadLetter])
    }
  }

  "An Actor with bounded Stash" must {

    "throw a StashOverflowException in case of a stash capacity violation" in {
      val stasher = system.actorOf(myProps(new StashingActorWithOverflow))
      // fill up stash
      (1 to 21) foreach { _ ⇒ stasher ! "hello" }
      expectMsg("STASHOVERFLOW")
    }
  }
}

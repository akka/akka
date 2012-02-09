/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._
import akka.dispatch.{ Await, MessageQueueAppendFailedException, BoundedDequeBasedMailbox }
import akka.pattern.ask
import akka.util.duration._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach

object ActorWithBoundedStashSpec {

  class StashingActor(implicit sys: ActorSystem) extends Actor with Stash {
    def receive = {
      case "hello" ⇒
        stash()
      case "world" ⇒
        self ! "world"
        try {
          unstashAll()
        } catch {
          case e: MessageQueueAppendFailedException ⇒
            expectedException.open()
        }
    }
  }

  @volatile var expectedException: TestLatch = null

  val testConf: Config = ConfigFactory.parseString("""
      akka {
        actor {
          default-dispatcher {
            mailboxType = "akka.actor.ActorWithBoundedStashSpec$Bounded"
          }
        }
      }
      """)

  // bounded deque-based mailbox with capacity 10
  class Bounded(config: Config) extends BoundedDequeBasedMailbox(10, 10 seconds)

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorWithBoundedStashSpec extends AkkaSpec(ActorWithBoundedStashSpec.testConf) with DefaultTimeout with BeforeAndAfterEach {
  import ActorWithBoundedStashSpec._

  implicit val sys = system

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[Exception]("Crashing...")))
  }

  "An Actor with Stash and BoundedDequeBasedMailbox" must {

    "throw a MessageQueueAppendFailedException in case of a capacity violation" in {
      ActorWithBoundedStashSpec.expectedException = new TestLatch
      val stasher = system.actorOf(Props(new StashingActor))
      // fill up stash
      val futures = for (_ ← 1 to 10) yield { stasher ? "hello" }
      futures foreach { Await.ready(_, 10 seconds) }

      // cause unstashAll with capacity violation
      stasher ! "world"
      Await.ready(ActorWithBoundedStashSpec.expectedException, 10 seconds)
    }

  }
}

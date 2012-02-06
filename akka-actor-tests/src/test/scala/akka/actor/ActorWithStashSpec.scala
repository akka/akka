/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.util.duration._
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach

object ActorWithStashSpec {

  class StashingActor(implicit sys: ActorSystem) extends Actor with Stash {
    def greeted: Receive = {
      case "bye" ⇒
        state.s = "bye"
        state.finished.await
      case _ ⇒ // do nothing
    }

    def receive = {
      case "hello" ⇒
        state.s = "hello"
        unstashAll(greeted)
      case msg ⇒ stash()
    }
  }

  class ActorWithProtocol(implicit sys: ActorSystem) extends Actor with Stash {
    def receive = {
      case "open" ⇒
        unstashAll {
          case "write" ⇒ // do writing...
          case "close" ⇒
            unstashAll()
            context.unbecome()
          case msg ⇒ stash()
        }
      case "done" ⇒ state.finished.await
      case msg    ⇒ stash()
    }
  }

  object state {
    @volatile
    var s: String = ""
    val finished = TestBarrier(2)
  }

  val testConf: Config = ConfigFactory.parseString("""
      akka {
        actor {
          default-dispatcher {
            mailboxType = "akka.dispatch.UnboundedDequeBasedMailbox"
          }
        }
      }
      """)

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorWithStashSpec(system: ActorSystem) extends AkkaSpec with BeforeAndAfterEach {
  import ActorWithStashSpec._

  def this() = this(ActorSystem("ActorWithStashSpec", ActorWithStashSpec.testConf))

  implicit val sys = system

  override def beforeEach() = {
    state.finished.reset
  }

  "An Actor" must {

    "stash messages" in {
      val stasher = system.actorOf(Props(new StashingActor))
      stasher ! "bye"
      stasher ! "hello"
      state.finished.await
      state.s must be("bye")
    }

    "support protocols" in {
      val protoActor = system.actorOf(Props(new ActorWithProtocol))
      protoActor ! "open"
      protoActor ! "write"
      protoActor ! "open"
      protoActor ! "close"
      protoActor ! "write"
      protoActor ! "close"
      protoActor ! "done"
      state.finished.await
    }

  }
}

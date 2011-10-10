/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.{ WordSpec, BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor._
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic._

object DeathWatchSpec {

}

class DeathWatchSpec extends WordSpec with MustMatchers with TestKit with BeforeAndAfterEach {
  import DeathWatchSpec._

  "The Death Watch" must {
    def expectTerminationOf(actorRef: ActorRef) = expectMsgPF(5 seconds, "stopped") {
      case Terminated(`actorRef`, ex: ActorKilledException) if ex.getMessage == "Stopped" ⇒ true
    }

    "notify with one Terminated message when an Actor is stopped" in {
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ context.self.stop() }))

      testActor link terminal

      terminal ! "anything"

      expectTerminationOf(terminal)

      terminal.stop()
    }

    "notify with all monitors with one Terminated message when an Actor is stopped" in {
      val monitor1, monitor2 = actorOf(Props(context ⇒ { case t: Terminated ⇒ testActor ! t }))
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ context.self.stop() }))

      monitor1 link terminal
      monitor2 link terminal
      testActor link terminal

      terminal ! "anything"

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      terminal.stop()
      monitor1.stop()
      monitor2.stop()
    }

    "notify with _current_ monitors with one Terminated message when an Actor is stopped" in {
      val monitor1, monitor2 = actorOf(Props(context ⇒ { case t: Terminated ⇒ testActor ! t }))
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ context.self.stop() }))

      monitor1 link terminal
      monitor2 link terminal
      testActor link terminal

      monitor2 unlink terminal

      terminal ! "anything"

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      terminal.stop()
      monitor1.stop()
      monitor2.stop()
    }

    "notify with a Terminated message once when an Actor is stopped but not when restarted" in {
      filterException[ActorKilledException] {
        val supervisor = actorOf(Props(context ⇒ { case _ ⇒ }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(2))))
        val terminal = actorOf(Props(context ⇒ { case x ⇒ context.channel ! x }).withSupervisor(supervisor))

        testActor link terminal

        terminal ! Kill
        terminal ! Kill
        terminal ! "foo"

        expectMsg("foo") //Make sure that it's still alive

        terminal ! Kill

        expectTerminationOf(terminal)

        terminal.stop()
        supervisor.stop()
      }
    }
  }

}

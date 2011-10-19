/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.BeforeAndAfterEach
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic._

class DeathWatchSpec extends AkkaSpec with BeforeAndAfterEach with ImplicitSender {

  "The Death Watch" must {
    def expectTerminationOf(actorRef: ActorRef) = expectMsgPF(2 seconds, actorRef + ": Stopped") {
      case Terminated(`actorRef`, ex: ActorKilledException) if ex.getMessage == "Stopped" || ex.getMessage == "Already terminated when linking" ⇒ true
    }

    "notify with one Terminated message when an Actor is stopped" in {
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ context.self.stop() }))

      testActor startsMonitoring terminal

      terminal ! "anything"

      expectTerminationOf(terminal)

      terminal.stop()
    }

    "notify with all monitors with one Terminated message when an Actor is stopped" in {
      val monitor1, monitor2 = actorOf(Props(context ⇒ { case t: Terminated ⇒ testActor ! t }))
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ context.self.stop() }))

      monitor1 startsMonitoring terminal
      monitor2 startsMonitoring terminal
      testActor startsMonitoring terminal

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

      monitor1 startsMonitoring terminal
      monitor2 startsMonitoring terminal
      testActor startsMonitoring terminal

      monitor2 stopsMonitoring terminal

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

        testActor startsMonitoring terminal

        terminal ! Kill
        terminal ! Kill
        (terminal ? "foo").as[String] must be === Some("foo")
        terminal ! Kill

        expectTerminationOf(terminal)

        terminal.stop()
        supervisor.stop()
      }
    }
  }

}

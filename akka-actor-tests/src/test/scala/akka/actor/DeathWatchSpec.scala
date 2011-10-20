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
    def expectTerminationOf(actorRef: ActorRef) = expectMsgPF(5 seconds, actorRef + ": Stopped or Already terminated when linking") {
      case Terminated(`actorRef`, ex: ActorKilledException) if ex.getMessage == "Stopped" || ex.getMessage == "Already terminated when linking" ⇒ true
    }

    "notify with one Terminated message when an Actor is stopped" in {
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ }))

      testActor startsMonitoring terminal

      testActor ! "ping"
      expectMsg("ping")

      terminal ! PoisonPill

      expectTerminationOf(terminal)
    }

    "notify with all monitors with one Terminated message when an Actor is stopped" in {
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ }))
      val monitor1, monitor2, monitor3 =
        actorOf(Props(new Actor {
          watch(terminal)
          def receive = { case t: Terminated ⇒ testActor ! t }
        }))

      terminal ! PoisonPill

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      monitor1.stop()
      monitor2.stop()
      monitor3.stop()
    }

    "notify with _current_ monitors with one Terminated message when an Actor is stopped" in {
      val terminal = actorOf(Props(context ⇒ { case _ ⇒ }))
      val monitor1, monitor3 =
        actorOf(Props(new Actor {
          watch(terminal)
          def receive = { case t: Terminated ⇒ testActor ! t }
        }))
      val monitor2 = actorOf(Props(new Actor {
        watch(terminal)
        unwatch(terminal)
        def receive = {
          case "ping"        ⇒ sender ! "pong"
          case t: Terminated ⇒ testActor ! t
        }
      }))

      monitor2 ! "ping"

      expectMsg("pong") //Needs to be here since startsMonitoring and stopsMonitoring are asynchronous

      terminal ! PoisonPill

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      monitor1.stop()
      monitor2.stop()
      monitor3.stop()
    }

    "notify with a Terminated message once when an Actor is stopped but not when restarted" in {
      filterException[ActorKilledException] {
        val supervisor = actorOf(Props(context ⇒ { case _ ⇒ }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(2))))
        val terminal = actorOf(Props(context ⇒ { case x ⇒ context.channel ! x }).withSupervisor(supervisor))
        val monitor = actorOf(Props(new Actor {
          watch(terminal)
          def receive = { case t: Terminated ⇒ testActor ! t }
        }).withSupervisor(supervisor))

        terminal ! Kill
        terminal ! Kill
        (terminal ? "foo").as[String] must be === Some("foo")
        terminal ! Kill

        expectTerminationOf(terminal)
        terminal.isShutdown must be === true

        supervisor.stop()
      }
    }
  }

}

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.BeforeAndAfterEach
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DeathWatchSpec extends AkkaSpec with BeforeAndAfterEach with ImplicitSender {

  "The Death Watch" must {
    def expectTerminationOf(actorRef: ActorRef) = expectMsgPF(5 seconds, actorRef + ": Stopped or Already terminated when linking") {
      case Terminated(`actorRef`) ⇒ true
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
        val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(2))))
        val terminalProps = Props(context ⇒ { case x ⇒ context.sender ! x })
        val terminal = (supervisor ? terminalProps).as[ActorRef].get

        val monitor = actorOf(Props(new Actor {
          watch(terminal)
          def receive = { case t: Terminated ⇒ testActor ! t }
        }))

        terminal ! Kill
        terminal ! Kill
        (terminal ? "foo").as[String] must be === Some("foo")
        terminal ! Kill

        expectTerminationOf(terminal)
        terminal.isShutdown must be === true

        supervisor.stop()
      }
    }

    "fail a monitor which does not handle Terminated()" in {
      filterEvents(EventFilter[ActorKilledException](), EventFilter[DeathPactException]()) {
        case class FF(fail: Failed)
        val supervisor = actorOf(Props[Supervisor]
          .withFaultHandler(new OneForOneStrategy(FaultHandlingStrategy.makeDecider(List(classOf[Exception])), Some(0)) {
            override def handleFailure(fail: Failed, stats: ChildRestartStats, children: Iterable[(ActorRef, ChildRestartStats)]) = {
              testActor ! FF(fail)
              super.handleFailure(fail, stats, children)
            }
          }))

        val failed, brother = (supervisor ? Props.empty).as[ActorRef].get
        brother startsMonitoring failed
        testActor startsMonitoring brother

        failed ! Kill
        val result = receiveWhile(3 seconds, messages = 3) {
          case FF(Failed(`failed`, _: ActorKilledException))       ⇒ 1
          case FF(Failed(`brother`, DeathPactException(`failed`))) ⇒ 2
          case Terminated(`brother`)                               ⇒ 3
        }
        testActor must not be 'shutdown
        result must be(Seq(1, 2, 3))
      }
    }
  }

}

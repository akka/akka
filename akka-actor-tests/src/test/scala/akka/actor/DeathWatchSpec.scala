/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic._
import akka.dispatch.Await
import akka.pattern.ask

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LocalDeathWatchSpec extends AkkaSpec with ImplicitSender with DefaultTimeout with DeathWatchSpec

object DeathWatchSpec {
  def props(target: ActorRef, testActor: ActorRef) = Props(new Actor {
    context.watch(target)
    def receive = { case x ⇒ testActor forward x }
  })
}

trait DeathWatchSpec { this: AkkaSpec with ImplicitSender with DefaultTimeout ⇒

  import DeathWatchSpec._

  lazy val supervisor = system.actorOf(Props(new Supervisor(SupervisorStrategy.defaultStrategy)), "watchers")

  def startWatching(target: ActorRef) = Await.result((supervisor ? props(target, testActor)).mapTo[ActorRef], 3 seconds)

  "The Death Watch" must {
    def expectTerminationOf(actorRef: ActorRef) = expectMsgPF(5 seconds, actorRef + ": Stopped or Already terminated when linking") {
      case Terminated(`actorRef`) ⇒ true
    }

    "notify with one Terminated message when an Actor is stopped" in {
      val terminal = system.actorOf(Props.empty)
      startWatching(terminal) ! "hallo"
      expectMsg("hallo") // this ensures that the DaemonMsgWatch has been received before we send the PoisonPill

      terminal ! PoisonPill

      expectTerminationOf(terminal)
    }

    "notify with one Terminated message when an Actor is already dead" in {
      val terminal = system.actorOf(Props.empty)

      terminal ! PoisonPill

      startWatching(terminal)
      expectTerminationOf(terminal)
    }

    "notify with all monitors with one Terminated message when an Actor is stopped" in {
      val terminal = system.actorOf(Props.empty)
      val monitor1, monitor2, monitor3 = startWatching(terminal)

      terminal ! PoisonPill

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      system.stop(monitor1)
      system.stop(monitor2)
      system.stop(monitor3)
    }

    "notify with _current_ monitors with one Terminated message when an Actor is stopped" in {
      val terminal = system.actorOf(Props.empty)
      val monitor1, monitor3 = startWatching(terminal)
      val monitor2 = system.actorOf(Props(new Actor {
        context.watch(terminal)
        context.unwatch(terminal)
        def receive = {
          case "ping"        ⇒ sender ! "pong"
          case t: Terminated ⇒ testActor ! t
        }
      }))

      monitor2 ! "ping"

      expectMsg("pong") //Needs to be here since watch and unwatch are asynchronous

      terminal ! PoisonPill

      expectTerminationOf(terminal)
      expectTerminationOf(terminal)

      system.stop(monitor1)
      system.stop(monitor2)
      system.stop(monitor3)
    }

    "notify with a Terminated message once when an Actor is stopped but not when restarted" in {
      filterException[ActorKilledException] {
        val supervisor = system.actorOf(Props(new Supervisor(
          OneForOneStrategy(maxNrOfRetries = 2)(List(classOf[Exception])))))
        val terminalProps = Props(context ⇒ { case x ⇒ context.sender ! x })
        val terminal = Await.result((supervisor ? terminalProps).mapTo[ActorRef], timeout.duration)

        val monitor = startWatching(terminal)

        terminal ! Kill
        terminal ! Kill
        Await.result(terminal ? "foo", timeout.duration) must be === "foo"
        terminal ! Kill

        expectTerminationOf(terminal)
        terminal.isTerminated must be === true

        system.stop(supervisor)
      }
    }

    "fail a monitor which does not handle Terminated()" in {
      filterEvents(EventFilter[ActorKilledException](), EventFilter[DeathPactException]()) {
        case class FF(fail: Failed)
        val strategy = new OneForOneStrategy(maxNrOfRetries = 0)(SupervisorStrategy.makeDecider(List(classOf[Exception]))) {
          override def handleFailure(context: ActorContext, child: ActorRef, cause: Throwable, stats: ChildRestartStats, children: Iterable[ChildRestartStats]) = {
            testActor.tell(FF(Failed(cause)), child)
            super.handleFailure(context, child, cause, stats, children)
          }
        }
        val supervisor = system.actorOf(Props(new Supervisor(strategy)))

        val failed = Await.result((supervisor ? Props.empty).mapTo[ActorRef], timeout.duration)
        val brother = Await.result((supervisor ? Props(new Actor {
          context.watch(failed)
          def receive = Actor.emptyBehavior
        })).mapTo[ActorRef], timeout.duration)

        startWatching(brother)

        failed ! Kill
        val result = receiveWhile(3 seconds, messages = 3) {
          case FF(Failed(_: ActorKilledException)) if lastSender eq failed ⇒ 1
          case FF(Failed(DeathPactException(`failed`))) if lastSender eq brother ⇒ 2
          case Terminated(`brother`) ⇒ 3
        }
        testActor.isTerminated must not be true
        result must be(Seq(1, 2, 3))
      }
    }
  }

}

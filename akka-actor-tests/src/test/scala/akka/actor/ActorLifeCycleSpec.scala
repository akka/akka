/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.actor

import language.postfixOps

import org.scalatest.BeforeAndAfterEach

import akka.actor.Actor._
import akka.testkit._
import scala.concurrent.duration._
import java.util.concurrent.atomic._
import scala.concurrent.Await
import akka.pattern.ask
import java.util.UUID.{ randomUUID ⇒ newUuid }

object ActorLifeCycleSpec {

  class LifeCycleTestActor(testActor: ActorRef, id: String, generationProvider: AtomicInteger) extends Actor {
    def report(msg: Any) = testActor ! message(msg)
    def message(msg: Any): Tuple3[Any, String, Int] = (msg, id, currentGen)
    val currentGen = generationProvider.getAndIncrement()
    override def preStart() { report("preStart") }
    override def postStop() { report("postStop") }
    def receive = { case "status" ⇒ sender() ! message("OK") }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorLifeCycleSpec extends AkkaSpec("akka.actor.serialize-messages=off") with BeforeAndAfterEach with ImplicitSender with DefaultTimeout {
  import ActorLifeCycleSpec._

  "An Actor" must {

    "invoke preRestart, preStart, postRestart when using OneForOneStrategy" in {
      filterException[ActorKilledException] {
        val id = newUuid.toString
        val supervisor = system.actorOf(Props(classOf[Supervisor], OneForOneStrategy(maxNrOfRetries = 3)(List(classOf[Exception]))))
        val gen = new AtomicInteger(0)
        val restarterProps = Props(new LifeCycleTestActor(testActor, id, gen) {
          override def preRestart(reason: Throwable, message: Option[Any]) { report("preRestart") }
          override def postRestart(reason: Throwable) { report("postRestart") }
        }).withDeploy(Deploy.local)
        val restarter = Await.result((supervisor ? restarterProps).mapTo[ActorRef], timeout.duration)

        expectMsg(("preStart", id, 0))
        restarter ! Kill
        expectMsg(("preRestart", id, 0))
        expectMsg(("postRestart", id, 1))
        restarter ! "status"
        expectMsg(("OK", id, 1))
        restarter ! Kill
        expectMsg(("preRestart", id, 1))
        expectMsg(("postRestart", id, 2))
        restarter ! "status"
        expectMsg(("OK", id, 2))
        restarter ! Kill
        expectMsg(("preRestart", id, 2))
        expectMsg(("postRestart", id, 3))
        restarter ! "status"
        expectMsg(("OK", id, 3))
        restarter ! Kill
        expectMsg(("postStop", id, 3))
        expectNoMsg(1 seconds)
        system.stop(supervisor)
      }
    }

    "default for preRestart and postRestart is to call postStop and preStart respectively" in {
      filterException[ActorKilledException] {
        val id = newUuid().toString
        val supervisor = system.actorOf(Props(classOf[Supervisor], OneForOneStrategy(maxNrOfRetries = 3)(List(classOf[Exception]))))
        val gen = new AtomicInteger(0)
        val restarterProps = Props(classOf[LifeCycleTestActor], testActor, id, gen)
        val restarter = Await.result((supervisor ? restarterProps).mapTo[ActorRef], timeout.duration)

        expectMsg(("preStart", id, 0))
        restarter ! Kill
        expectMsg(("postStop", id, 0))
        expectMsg(("preStart", id, 1))
        restarter ! "status"
        expectMsg(("OK", id, 1))
        restarter ! Kill
        expectMsg(("postStop", id, 1))
        expectMsg(("preStart", id, 2))
        restarter ! "status"
        expectMsg(("OK", id, 2))
        restarter ! Kill
        expectMsg(("postStop", id, 2))
        expectMsg(("preStart", id, 3))
        restarter ! "status"
        expectMsg(("OK", id, 3))
        restarter ! Kill
        expectMsg(("postStop", id, 3))
        expectNoMsg(1 seconds)
        system.stop(supervisor)
      }
    }

    "not invoke preRestart and postRestart when never restarted using OneForOneStrategy" in {
      val id = newUuid().toString
      val supervisor = system.actorOf(Props(classOf[Supervisor],
        OneForOneStrategy(maxNrOfRetries = 3)(List(classOf[Exception]))))
      val gen = new AtomicInteger(0)
      val props = Props(classOf[LifeCycleTestActor], testActor, id, gen)
      val a = Await.result((supervisor ? props).mapTo[ActorRef], timeout.duration)
      expectMsg(("preStart", id, 0))
      a ! "status"
      expectMsg(("OK", id, 0))
      system.stop(a)
      expectMsg(("postStop", id, 0))
      expectNoMsg(1 seconds)
      system.stop(supervisor)
    }

    "log failues in postStop" in {
      val a = system.actorOf(Props(new Actor {
        def receive = Actor.emptyBehavior
        override def postStop { throw new Exception("hurrah") }
      }))
      EventFilter[Exception]("hurrah", occurrences = 1) intercept {
        a ! PoisonPill
      }
    }

    "clear the behavior stack upon restart" in {
      final case class Become(recv: ActorContext ⇒ Receive)
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case Become(beh) ⇒ { context.become(beh(context), discardOld = false); sender() ! "ok" }
          case x           ⇒ sender() ! 42
        }
      }))
      a ! "hello"
      expectMsg(42)
      a ! Become(ctx ⇒ {
        case "fail" ⇒ throw new RuntimeException("buh")
        case x      ⇒ ctx.sender() ! 43
      })
      expectMsg("ok")
      a ! "hello"
      expectMsg(43)
      EventFilter[RuntimeException]("buh", occurrences = 1) intercept {
        a ! "fail"
      }
      a ! "hello"
      expectMsg(42)
    }
  }

}

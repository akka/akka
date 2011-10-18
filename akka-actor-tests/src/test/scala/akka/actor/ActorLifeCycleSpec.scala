/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.MustMatchers

import akka.actor.Actor._
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic._

object ActorLifeCycleSpec {

}

class ActorLifeCycleSpec extends AkkaSpec with BeforeAndAfterEach with ImplicitSender {
  import ActorLifeCycleSpec._

  class LifeCycleTestActor(id: String, generationProvider: AtomicInteger) extends Actor {
    def report(msg: Any) = testActor ! message(msg)
    def message(msg: Any): Tuple3[Any, String, Int] = (msg, id, currentGen)
    val currentGen = generationProvider.getAndIncrement()
    override def preStart() { report("preStart") }
    override def postStop() { report("postStop") }
    def receive = { case "status" ⇒ this reply message("OK") }
  }

  "An Actor" must {

    "invoke preRestart, preStart, postRestart when using OneForOneStrategy" in {
      filterException[ActorKilledException] {
        val id = newUuid().toString
        val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(3))))
        val gen = new AtomicInteger(0)
        val restarterProps = Props(new LifeCycleTestActor(id, gen) {
          override def preRestart(reason: Throwable, message: Option[Any]) { report("preRestart") }
          override def postRestart(reason: Throwable) { report("postRestart") }
        })
        val restarter = (supervisor ? restarterProps).as[ActorRef].get

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
        supervisor.stop
      }
    }

    "default for preRestart and postRestart is to call postStop and preStart respectively" in {
      filterException[ActorKilledException] {
        val id = newUuid().toString
        val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(3))))
        val gen = new AtomicInteger(0)
        val restarterProps = Props(new LifeCycleTestActor(id, gen))
        val restarter = (supervisor ? restarterProps).as[ActorRef].get

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
        supervisor.stop
      }
    }

    "not invoke preRestart and postRestart when never restarted using OneForOneStrategy" in {
      val id = newUuid().toString
      val supervisor = actorOf(Props[Supervisor].withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(3))))
      val gen = new AtomicInteger(0)
      val props = Props(new LifeCycleTestActor(id, gen))
      val a = (supervisor ? props).as[ActorRef].get
      expectMsg(("preStart", id, 0))
      a ! "status"
      expectMsg(("OK", id, 0))
      a.stop
      expectMsg(("postStop", id, 0))
      expectNoMsg(1 seconds)
      supervisor.stop
    }
  }

}

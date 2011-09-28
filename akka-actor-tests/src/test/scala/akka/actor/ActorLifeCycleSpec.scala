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

object ActorLifeCycleSpec {
}

class ActorLifeCycleSpec extends WordSpec with MustMatchers with TestKit with BeforeAndAfterEach {
  import ActorRestartSpec._

  "An Actor" must {

    "invoke preRestart, preStart, postRestart when using OneForOneStrategy" in {
      filterEvents(EventFilter[ActorKilledException] :: Nil) {
        val gen = new AtomicInteger(0)
        val supervisor = actorOf(Props(self ⇒ { case _ ⇒ }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(3))))

        val restarter = actorOf(Props(new Actor {
          val currentGen = gen.getAndIncrement()
          override def preStart() { testActor ! (("preStart", currentGen)) }
          override def postStop() { testActor ! (("postStop", currentGen)) }
          override def preRestart(reason: Throwable, message: Option[Any]) { testActor ! (("preRestart", currentGen)) }
          override def postRestart(reason: Throwable) { testActor ! (("postRestart", currentGen)) }
          def receive = { case "status" ⇒ this reply (("OK", currentGen)) }
        }).withSupervisor(supervisor))

        expectMsg(("preStart", 0))
        restarter ! Kill
        expectMsg(("preRestart", 0))
        expectMsg(("postRestart", 1))
        restarter ! "status"
        expectMsg(("OK", 1))
        restarter ! Kill
        expectMsg(("preRestart", 1))
        expectMsg(("postRestart", 2))
        restarter ! "status"
        expectMsg(("OK", 2))
        restarter ! Kill
        expectMsg(("preRestart", 2))
        expectMsg(("postRestart", 3))
        restarter ! "status"
        expectMsg(("OK", 3))
        restarter ! Kill
        expectMsg(("postStop", 3))
      }
    }

  }

}

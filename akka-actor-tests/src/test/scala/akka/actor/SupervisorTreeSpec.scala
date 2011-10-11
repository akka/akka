/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.util.duration._
import akka.testkit.Testing.sleepFor
import akka.dispatch.Dispatchers
import akka.actor.Actor._
import akka.testkit.{ TestKit, EventFilter, filterEvents, filterException }
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

class SupervisorTreeSpec extends AkkaSpec with ImplicitSender {

  "In a 3 levels deep supervisor tree (linked in the constructor) we" must {

    "be able to kill the middle actor and see itself and its child restarted" in {
      filterException[ActorKilledException] {
        within(5 seconds) {
          val p = Props(new Actor {
            def receive = { case false ⇒ }
            override def preRestart(reason: Throwable, msg: Option[Any]) { testActor ! self.address }
          }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, 1000))
          val headActor = createActor(p)
          val middleActor = createActor(p.withSupervisor(headActor))
          val lastActor = createActor(p.withSupervisor(middleActor))

          middleActor ! Kill
          expectMsg(middleActor.address)
          expectMsg(lastActor.address)
          expectNoMsg(2 seconds)
          headActor.stop()
        }
      }
    }
  }
}

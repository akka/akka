/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.util.duration._
import akka.dispatch.Dispatchers
import akka.actor.Actor._
import akka.testkit.{ TestKit, EventFilter, filterEvents, filterException }
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorTreeSpec extends AkkaSpec with ImplicitSender {

  "In a 3 levels deep supervisor tree (linked in the constructor) we" must {

    "be able to kill the middle actor and see itself and its child restarted" in {
      EventFilter[ActorKilledException](occurrences = 1) intercept {
        within(5 seconds) {
          val p = Props(new Actor {
            def receive = {
              case p: Props ⇒ sender ! context.actorOf(p)
            }
            override def preRestart(cause: Throwable, msg: Option[Any]) { testActor ! self.path }
          }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 3, 1000))
          val headActor = actorOf(p)
          val middleActor = (headActor ? p).as[ActorRef].get
          val lastActor = (middleActor ? p).as[ActorRef].get

          middleActor ! Kill
          expectMsg(middleActor.path)
          expectMsg(lastActor.path)
          expectNoMsg(2 seconds)
          headActor.stop()
        }
      }
    }
  }
}

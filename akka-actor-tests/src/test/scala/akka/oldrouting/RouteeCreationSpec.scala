/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.oldrouting

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.LocalActorRef
import scala.concurrent.duration._
import akka.routing._

class RouteeCreationSpec extends AkkaSpec {

  "Creating Routees" must {

    "result in visible routees" in {
      val N = 100
      system.actorOf(Props(new Actor {
        testActor ! system.actorFor(self.path)
        def receive = Actor.emptyBehavior
      }).withRouter(RoundRobinRouter(N)))
      for (i ← 1 to N) {
        expectMsgType[ActorRef] match {
          case _: LocalActorRef ⇒ // fine
          case x                ⇒ fail(s"routee $i was a ${x.getClass}")
        }
      }
    }

    "allow sending to context.parent" in {
      val N = 100
      system.actorOf(Props(new Actor {
        context.parent ! "one"
        def receive = {
          case "one" ⇒ testActor forward "two"
        }
      }).withRouter(RoundRobinRouter(N)))
      val gotit = receiveWhile(messages = N) {
        case "two" ⇒ lastSender.toString
      }
      expectNoMsg(100.millis)
      if (gotit.size != N) {
        fail(s"got only ${gotit.size} from \n${gotit mkString "\n"}")
      }
    }

  }

}

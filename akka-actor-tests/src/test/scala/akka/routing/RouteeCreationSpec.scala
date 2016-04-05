/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.routing

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.Actor
import scala.concurrent.duration._
import akka.actor.Identify
import akka.actor.ActorIdentity

class RouteeCreationSpec extends AkkaSpec {

  "Creating Routees" must {

    "result in visible routees" in {
      val N = 100
      system.actorOf(RoundRobinPool(N).props(Props(new Actor {
        system.actorSelection(self.path).tell(Identify(self.path), testActor)
        def receive = Actor.emptyBehavior
      })))
      for (i ← 1 to N) {
        expectMsgType[ActorIdentity] match {
          case ActorIdentity(_, Some(_)) ⇒ // fine
          case x                         ⇒ fail(s"routee $i was not found $x")
        }
      }
    }

    "allow sending to context.parent" in {
      val N = 100
      system.actorOf(RoundRobinPool(N).props(Props(new Actor {
        context.parent ! "one"
        def receive = {
          case "one" ⇒ testActor forward "two"
        }
      })))
      val gotit = receiveWhile(messages = N) {
        case "two" ⇒ lastSender.toString
      }
      expectNoMsg(100.millis)
      if (gotit.size != N) {
        fail(s"got only ${gotit.size} from [${gotit mkString ", "}]")
      }
    }

  }

}

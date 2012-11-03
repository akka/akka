/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.Actor
import akka.testkit.ImplicitSender
import scala.concurrent.duration._
import akka.actor.FSM
import akka.actor.ActorRef

class ReliableProxyDocSpec extends AkkaSpec with ImplicitSender {

  "A ReliableProxy" must {

    "show state transitions" in {
      val target = system.deadLetters
      val a = system.actorOf(Props(new Actor {
        //#demo-transition
        val proxy = context.actorOf(Props(new ReliableProxy(target, 100.millis)))
        proxy ! FSM.SubscribeTransitionCallBack(self)

        var client: ActorRef = _

        def receive = {
          case "go"                               ⇒ proxy ! 42; client = sender
          case FSM.CurrentState(`proxy`, initial) ⇒
          case FSM.Transition(`proxy`, from, to) ⇒ if (to == ReliableProxy.Idle)
            client ! "done"
        }
        //#demo-transition
      }))
      a ! "go"
      expectMsg("done")
    }

  }

}

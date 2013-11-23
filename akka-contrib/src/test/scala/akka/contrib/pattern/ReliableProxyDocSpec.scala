/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.Actor
import akka.testkit.ImplicitSender
import scala.concurrent.duration._
import akka.actor.FSM
import akka.actor.ActorRef
import akka.testkit.TestProbe

object ReliableProxyDocSpec {

  //#demo
  import akka.contrib.pattern.ReliableProxy

  class ProxyParent(target: ActorRef) extends Actor {
    val proxy = context.actorOf(ReliableProxy.props(target, 100.millis, 120.seconds))

    def receive = {
      case "hello" ⇒ proxy ! "world!"
    }
  }
  //#demo

  //#demo-transition
  class ProxyTransitionParent(target: ActorRef) extends Actor {
    val proxy = context.actorOf(ReliableProxy.props(target, 100.millis, 120.seconds))
    proxy ! FSM.SubscribeTransitionCallBack(self)

    var client: ActorRef = _

    def receive = {
      case "go" ⇒
        proxy ! 42
        client = sender()
      case FSM.CurrentState(`proxy`, initial) ⇒
      case FSM.Transition(`proxy`, from, to) ⇒
        if (to == ReliableProxy.Idle)
          client ! "done"
    }
  }
  //#demo-transition
}

class ReliableProxyDocSpec extends AkkaSpec with ImplicitSender {

  import ReliableProxyDocSpec._

  "A ReliableProxy" must {

    "show usage" in {
      val target = testActor
      val a = system.actorOf(Props(classOf[ProxyParent], target))
      a ! "hello"
      expectMsg("world!")
    }

    "show state transitions" in {
      val target = system.deadLetters
      val a = system.actorOf(Props(classOf[ProxyTransitionParent], target))
      a ! "go"
      expectMsg("done")
    }

  }

}

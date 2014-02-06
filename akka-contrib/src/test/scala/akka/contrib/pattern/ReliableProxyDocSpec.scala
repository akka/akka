/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import akka.testkit.AkkaSpec
import akka.actor._
import akka.testkit.ImplicitSender
import scala.concurrent.duration._

object ReliableProxyDocSpec {

  //#demo
  import akka.contrib.pattern.ReliableProxy

  class ProxyParent(targetPath: ActorPath) extends Actor {
    val proxy = context.actorOf(ReliableProxy.props(targetPath, 100.millis))

    def receive = {
      case "hello" ⇒ proxy ! "world!"
    }
  }
  //#demo

  //#demo-transition
  class ProxyTransitionParent(targetPath: ActorPath) extends Actor {
    val proxy = context.actorOf(ReliableProxy.props(targetPath, 100.millis))
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
      val a = system.actorOf(Props(classOf[ProxyParent], target.path))
      a ! "hello"
      expectMsg("world!")
    }

    "show state transitions" in {
      val target = system.deadLetters
      val a = system.actorOf(Props(classOf[ProxyTransitionParent], target.path))
      a ! "go"
      expectMsg("done")
    }

  }

}

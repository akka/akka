/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.pattern

import akka.testkit.AkkaSpec
import akka.actor._
import scala.concurrent.duration._
import akka.testkit.TestProbe

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

  class WatchingProxyParent(targetPath: ActorPath) extends Actor {
    val proxy = context.watch(context.actorOf(
      ReliableProxy.props(targetPath, 100.millis, reconnectAfter = 500.millis, maxReconnects = 3)))

    var client: Option[ActorRef] = None

    def receive = {
      case "hello" ⇒
        proxy ! "world!"
        client = Some(sender())
      case Terminated(`proxy`) ⇒
        client foreach { _ ! "terminated" }
    }
  }
}

class ReliableProxyDocSpec extends AkkaSpec {

  import ReliableProxyDocSpec._

  "A ReliableProxy" must {

    "show usage" in {
      val probe = TestProbe()
      val a = system.actorOf(Props(classOf[ProxyParent], probe.ref.path))
      a.tell("hello", probe.ref)
      probe.expectMsg("world!")
    }

    "show state transitions" in {
      val target = TestProbe().ref
      val probe = TestProbe()
      val a = system.actorOf(Props(classOf[ProxyTransitionParent], target.path))
      a.tell("go", probe.ref)
      probe.expectMsg("done")
    }

    "show terminated after maxReconnects" in within(5.seconds) {
      val target = system.deadLetters
      val probe = TestProbe()
      val a = system.actorOf(Props(classOf[WatchingProxyParent], target.path))
      a.tell("hello", probe.ref)
      probe.expectMsg("terminated")
    }

  }

}

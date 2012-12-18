/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import language.postfixOps

import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import org.scalatest.BeforeAndAfterEach
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.actor.Props
import akka.actor.Actor
import akka.testkit.ImplicitSender
import scala.concurrent.duration._
import akka.actor.FSM
import akka.actor.ActorRef
import akka.testkit.TestProbe

object ReliableProxySpec extends MultiNodeConfig {
  val local = role("local")
  val remote = role("remote")

  testTransport(on = true)
}

class ReliableProxyMultiJvmNode1 extends ReliableProxySpec
class ReliableProxyMultiJvmNode2 extends ReliableProxySpec

class ReliableProxySpec extends MultiNodeSpec(ReliableProxySpec) with STMultiNodeSpec with BeforeAndAfterEach with ImplicitSender {
  import ReliableProxySpec._
  import ReliableProxy._

  override def initialParticipants = 2

  override def afterEach {
    runOn(local) {
      testConductor.throttle(local, remote, Direction.Both, -1).await
    }
  }

  @volatile var target: ActorRef = system.deadLetters
  @volatile var proxy: ActorRef = system.deadLetters

  def expectState(s: State) = expectMsg(FSM.CurrentState(proxy, s))
  def expectTransition(s1: State, s2: State) = expectMsg(FSM.Transition(proxy, s1, s2))
  
  def sendN(n: Int) =  (1 to n) foreach (proxy ! _)
  def expectN(n: Int) = (1 to n) foreach { n ⇒ expectMsg(n); lastSender must be === target }

  "A ReliableProxy" must {

    "initialize properly" in {
      runOn(remote) {
        target = system.actorOf(Props(new Actor {
          def receive = {
            case x ⇒ testActor ! x
          }
        }), "echo")
      }

      enterBarrier("initialize")

      runOn(local) {
        //#demo
        import akka.contrib.pattern.ReliableProxy

        target = system.actorFor(node(remote) / "user" / "echo")
        proxy = system.actorOf(Props(new ReliableProxy(target, 100.millis)), "proxy")
        //#demo
        proxy ! FSM.SubscribeTransitionCallBack(testActor)
        expectState(Idle)
        //#demo
        proxy ! "hello"
        //#demo
        expectTransition(Idle, Active)
        expectTransition(Active, Idle)
      }

      runOn(remote) {
        expectMsg("hello")
      }
    }

    "forward messages in sequence" in {
      runOn(local) {
        sendN(100)
        expectTransition(Idle, Active)
        expectTransition(Active, Idle)
      }
      runOn(remote) {
        within(1 second) {
          expectN(100)
        }
      }
      
      enterBarrier("test1a")
      
      runOn(local) {
        sendN(100)
        expectTransition(Idle, Active)
        expectTransition(Active, Idle)
      }
      runOn(remote) {
        within(1 second) {
          expectN(100)
        }
      }
      
      enterBarrier("test1b")
    }

    "retry when sending fails" in {
      runOn(local) {
        testConductor.blackhole(local, remote, Direction.Send).await
        sendN(100)
        within(1 second) {
          expectTransition(Idle, Active)
          expectNoMsg
        }
      }
      
      enterBarrier("test2a")
      
      runOn(remote) {
        expectNoMsg(0 seconds)
      }
      
      enterBarrier("test2b")
      
      runOn(local) {
        testConductor.throttle(local, remote, Direction.Send, -1).await
        within(5 seconds) { expectTransition(Active, Idle) }
      }
      runOn(remote) {
        within(1 second) {
          expectN(100)
        }
      }
      
      enterBarrier("test2c")
    }

    "retry when receiving fails" in {
      runOn(local) {
        testConductor.blackhole(local, remote, Direction.Receive).await
        sendN(100)
        within(1 second) {
          expectTransition(Idle, Active)
          expectNoMsg
        }
      }
      runOn(remote) {
        within(1 second) {
          expectN(100)
        }
      }
      
      enterBarrier("test3a")
      
      runOn(local) {
        testConductor.throttle(local, remote, Direction.Receive, -1).await
        within(5 seconds) { expectTransition(Active, Idle) }
      }
      
      enterBarrier("test3b")
    }

    "resend across a slow link" in {
      runOn(local) {
        testConductor.throttle(local, remote, Direction.Send, rateMBit = 0.1).await
        sendN(50)
        within(5 seconds) {
          expectTransition(Idle, Active)
          expectTransition(Active, Idle)
        }
      }
      runOn(remote) {
        within(5 seconds) {
          expectN(50)
        }
      }
      
      enterBarrier("test4a")
      
      runOn(local) {
        testConductor.throttle(local, remote, Direction.Send, rateMBit = -1).await
        testConductor.throttle(local, remote, Direction.Receive, rateMBit = 0.1).await
        sendN(50)
        within(5 seconds) {
          expectTransition(Idle, Active)
          expectTransition(Active, Idle)
        }
      }
      runOn(remote) {
        within(1 second) {
          expectN(50)
        }
      }
      
      enterBarrier("test4a")
    }

  }
}

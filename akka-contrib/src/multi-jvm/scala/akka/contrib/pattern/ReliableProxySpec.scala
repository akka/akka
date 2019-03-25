/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.contrib.pattern

import language.postfixOps
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import org.scalatest.BeforeAndAfterEach
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.actor._
import akka.testkit.ImplicitSender

import scala.concurrent.duration._
import akka.actor.FSM
import akka.actor.ActorRef
import akka.testkit.TestKitExtension
import akka.actor.ActorIdentity
import akka.actor.Identify

object ReliableProxySpec extends MultiNodeConfig {
  val local = role("local")
  val remote = role("remote")

  testTransport(on = true)
}

class ReliableProxyMultiJvmNode1 extends ReliableProxySpec
class ReliableProxyMultiJvmNode2 extends ReliableProxySpec

class ReliableProxySpec
    extends MultiNodeSpec(ReliableProxySpec)
    with STMultiNodeSpec
    with BeforeAndAfterEach
    with ImplicitSender {
  import ReliableProxySpec._
  import ReliableProxy._

  override def initialParticipants = roles.size

  override def afterEach(): Unit = {
    runOn(local) {
      testConductor.passThrough(local, remote, Direction.Both).await
    }
    enterBarrier("after-each")
  }

  @volatile var target: ActorRef = system.deadLetters
  @volatile var proxy: ActorRef = system.deadLetters

  def idTarget(): Unit = {
    system.actorSelection(node(remote) / "user" / "echo") ! Identify("echo")
    target = expectMsgType[ActorIdentity].ref.get
  }

  def startTarget(): Unit = {
    target = system.actorOf(Props(new Actor {
      def receive = {
        case x => testActor ! x
      }
    }).withDeploy(Deploy.local), "echo")
  }

  def stopProxy(): Unit = {
    val currentProxy = proxy
    currentProxy ! FSM.UnsubscribeTransitionCallBack(testActor)
    currentProxy ! PoisonPill
    expectTerminated(currentProxy)
  }

  def expectState(s: State) = expectMsg(FSM.CurrentState(proxy, s))
  def expectTransition(s1: State, s2: State) = expectMsg(FSM.Transition(proxy, s1, s2))
  def expectTransition(max: FiniteDuration, s1: State, s2: State) = expectMsg(max, FSM.Transition(proxy, s1, s2))

  def sendN(n: Int) = (1 to n).foreach(proxy ! _)
  def expectN(n: Int) = (1 to n).foreach { n =>
    expectMsg(n); lastSender should ===(target)
  }

  // avoid too long timeout for expectNoMsg when using dilated timeouts, because
  // blackhole will trigger failure detection
  val expectNoMsgTimeout = {
    val timeFactor = TestKitExtension(system).TestTimeFactor
    if (timeFactor > 1.0) (1.0 / timeFactor).seconds else 1.second
  }

  "A ReliableProxy" must {

    "initialize properly" in {
      runOn(remote) {
        startTarget()
      }

      enterBarrier("initialize")

      runOn(local) {
        import akka.contrib.pattern.ReliableProxy

        idTarget()
        proxy = system.actorOf(ReliableProxy.props(target.path, 100.millis, 5.seconds), "proxy1")
        watch(proxy)
        proxy ! FSM.SubscribeTransitionCallBack(testActor)
        expectState(Connecting)
        proxy ! "hello"
        expectMsgType[TargetChanged]
        expectTransition(Connecting, Active)
        expectTransition(Active, Idle)
      }

      runOn(remote) {
        expectMsg(1.second, "hello")
      }

      enterBarrier("initialize-done")
    }

    "forward messages in sequence" in {
      runOn(local) {
        sendN(100)
        expectTransition(Idle, Active)
        expectTransition(Active, Idle)
      }
      runOn(remote) {
        within(5 seconds) {
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
        within(5 seconds) {
          expectN(100)
        }
      }

      enterBarrier("test1b")
    }

    "retry when sending fails" in {
      runOn(local) {
        testConductor.blackhole(local, remote, Direction.Send).await
        sendN(100)
        expectTransition(1 second, Idle, Active)
        expectNoMsg(expectNoMsgTimeout)
      }

      enterBarrier("test2a")

      runOn(remote) {
        expectNoMsg(0 seconds)
      }

      enterBarrier("test2b")

      runOn(local) {
        testConductor.passThrough(local, remote, Direction.Send).await
        expectTransition(5 seconds, Active, Idle)
      }
      runOn(remote) {
        within(5 seconds) {
          expectN(100)
        }
      }

      enterBarrier("test2c")
    }

    "retry when receiving fails" in {
      runOn(local) {
        testConductor.blackhole(local, remote, Direction.Receive).await
        sendN(100)
        expectTransition(1 second, Idle, Active)
        expectNoMsg(expectNoMsgTimeout)
      }
      runOn(remote) {
        within(5 second) {
          expectN(100)
        }
      }

      enterBarrier("test3a")

      runOn(local) {
        testConductor.passThrough(local, remote, Direction.Receive).await
        expectTransition(5 seconds, Active, Idle)
      }

      enterBarrier("test3b")
    }

    "resend across a slow outbound link" in {
      runOn(local) {
        // the rateMBit value is derived from empirical studies so that it will trigger resends,
        // the exact value is not important, but it should not be too large
        testConductor.throttle(local, remote, Direction.Send, rateMBit = 0.02).await
        sendN(50)
        within(5 seconds) {
          expectTransition(Idle, Active)
          // use the slow link for a while, which will trigger resends
          Thread.sleep(2000)
          // full speed, and it will catch up outstanding messages
          testConductor.passThrough(local, remote, Direction.Send).await
          expectTransition(Active, Idle)
        }
      }
      runOn(remote) {
        within(5 seconds) {
          expectN(50)
        }
        expectNoMsg(expectNoMsgTimeout)
      }

      enterBarrier("test4")
    }

    "resend across a slow inbound link" in {
      runOn(local) {
        testConductor.passThrough(local, remote, Direction.Send).await
        // the rateMBit value is derived from empirical studies so that it will trigger resends,
        // the exact value is not important, but it should not be too large
        testConductor.throttle(local, remote, Direction.Receive, rateMBit = 0.02).await
        sendN(50)
        within(5 seconds) {
          expectTransition(Idle, Active)
          // use the slow link for a while, which will trigger resends
          Thread.sleep(2000)
          // full speed, and it will catch up outstanding messages
          testConductor.passThrough(local, remote, Direction.Receive).await
          expectTransition(Active, Idle)
        }
      }
      runOn(remote) {
        within(5 second) {
          expectN(50)
        }
        expectNoMsg(1 seconds)
      }

      enterBarrier("test5")
    }

    "reconnect to target" in {
      runOn(remote) {
        // Stop the target
        system.stop(target)
      }

      runOn(local) {
        // After the target stops the proxy will change to Reconnecting
        within(5 seconds) {
          expectTransition(Idle, Connecting)
        }
        // Send some messages while it's reconnecting
        sendN(50)
      }

      enterBarrier("test6a")

      runOn(remote) {
        // Restart the target to have something to reconnect to
        startTarget()
      }

      runOn(local) {
        // After reconnecting a we'll get a TargetChanged message
        // and the proxy will transition to Active to send the outstanding messages
        within(10 seconds) {
          expectMsgType[TargetChanged]
          expectTransition(Connecting, Active)
        }
      }

      enterBarrier("test6b")

      runOn(local) {
        // After the messages have been delivered, proxy is back to idle
        expectTransition(Active, Idle)
      }

      runOn(remote) {
        expectN(50)
      }

      enterBarrier("test6c")
    }

    "stop proxy if target stops and no reconnection" in {
      runOn(local) {
        stopProxy() // Stop previous proxy

        // Start new proxy with no reconnections
        proxy = system.actorOf(ReliableProxy.props(target.path, 100.millis), "proxy2")
        proxy ! FSM.SubscribeTransitionCallBack(testActor)
        watch(proxy)

        expectState(Connecting)
        expectMsgType[TargetChanged]
        expectTransition(Connecting, Idle)
      }

      enterBarrier("test7a")

      runOn(remote) {
        // Stop the target, this will cause the proxy to stop
        system.stop(target)
      }

      runOn(local) {
        within(5 seconds) {
          expectMsgType[ProxyTerminated]
          expectTerminated(proxy)
        }
      }

      enterBarrier("test7b")

    }

    "stop proxy after max reconnections" in {
      runOn(remote) {
        // Target is not running after previous test, start it
        startTarget()
      }
      enterBarrier("target-started")

      runOn(local) {
        // Get new target's ref
        idTarget()
      }

      enterBarrier("test8a")

      runOn(local) {
        // Proxy is not running after previous test
        // Start new proxy with 3 reconnections every 2 sec
        proxy = system.actorOf(ReliableProxy.props(target.path, 100.millis, 2.seconds, 3), "proxy3")
        proxy ! FSM.SubscribeTransitionCallBack(testActor)
        watch(proxy)
        expectState(Connecting)
        expectMsgType[TargetChanged]
        expectTransition(Connecting, Idle)
      }

      enterBarrier("test8b")

      runOn(remote) {
        // Stop target
        system.stop(target)
      }

      runOn(local) {
        // Wait for transition to Connecting, then send messages
        within(5 seconds) {
          expectTransition(Idle, Connecting)
        }
        sendN(50)
      }

      enterBarrier("test8c")

      runOn(local) {
        // After max reconnections, proxy stops itself.  Expect ProxyTerminated(Unsent(msgs, sender, serial)).
        within(5 * 2.seconds) {
          val proxyTerm = expectMsgType[ProxyTerminated]
          // Validate that the unsent messages are 50 ints
          val unsentInts = proxyTerm.outstanding.queue.collect { case Message(i: Int, _, _) if i > 0 && i <= 50 => i }
          unsentInts should have size 50
          expectTerminated(proxy)
        }
      }

      enterBarrier("test8d")
    }
  }
}

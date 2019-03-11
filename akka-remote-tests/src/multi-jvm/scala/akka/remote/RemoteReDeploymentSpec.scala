/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.remote.testkit.MultiNodeConfig
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.remote.transport.ThrottlerTransportAdapter.Direction._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.ActorIdentity
import akka.actor.ActorLogging
import akka.actor.Identify
import akka.testkit.TestProbe

class RemoteReDeploymentConfig(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = true).withFallback(ConfigFactory.parseString(s"""akka.remote.transport-failure-detector {
         threshold=0.1
         heartbeat-interval=0.1s
         acceptable-heartbeat-pause=1s
       }
       akka.remote.watch-failure-detector {
         threshold=0.1
         heartbeat-interval=0.1s
         acceptable-heartbeat-pause=2.5s
       }
       akka.remote.artery.enabled = $artery
       akka.loglevel = INFO
       """)).withFallback(RemotingMultiNodeSpec.commonConfig))

  testTransport(on = true)

  deployOn(second, "/parent/hello.remote = \"@first@\"")
}

class RemoteReDeploymentFastMultiJvmNode1 extends RemoteReDeploymentFastMultiJvmSpec(artery = false)
class RemoteReDeploymentFastMultiJvmNode2 extends RemoteReDeploymentFastMultiJvmSpec(artery = false)

class ArteryRemoteReDeploymentFastMultiJvmNode1 extends RemoteReDeploymentFastMultiJvmSpec(artery = true)
class ArteryRemoteReDeploymentFastMultiJvmNode2 extends RemoteReDeploymentFastMultiJvmSpec(artery = true)

abstract class RemoteReDeploymentFastMultiJvmSpec(artery: Boolean)
    extends RemoteReDeploymentMultiJvmSpec(new RemoteReDeploymentConfig(artery)) {
  override def sleepAfterKill = 0.seconds // new association will come in while old is still “healthy”
  override def expectQuarantine = false
}

class RemoteReDeploymentMediumMultiJvmNode1 extends RemoteReDeploymentMediumMultiJvmSpec(artery = false)
class RemoteReDeploymentMediumMultiJvmNode2 extends RemoteReDeploymentMediumMultiJvmSpec(artery = false)

class ArteryRemoteReDeploymentMediumMultiJvmNode1 extends RemoteReDeploymentMediumMultiJvmSpec(artery = true)
class ArteryRemoteReDeploymentMediumMultiJvmNode2 extends RemoteReDeploymentMediumMultiJvmSpec(artery = true)

abstract class RemoteReDeploymentMediumMultiJvmSpec(artery: Boolean)
    extends RemoteReDeploymentMultiJvmSpec(new RemoteReDeploymentConfig(artery)) {
  override def sleepAfterKill =
    1.seconds // new association will come in while old is gated in ReliableDeliverySupervisor
  override def expectQuarantine = false
}

class RemoteReDeploymentSlowMultiJvmNode1 extends RemoteReDeploymentSlowMultiJvmSpec(artery = false)
class RemoteReDeploymentSlowMultiJvmNode2 extends RemoteReDeploymentSlowMultiJvmSpec(artery = false)

class ArteryRemoteReDeploymentSlowMultiJvmNode1 extends RemoteReDeploymentSlowMultiJvmSpec(artery = true)
class ArteryRemoteReDeploymentSlowMultiJvmNode2 extends RemoteReDeploymentSlowMultiJvmSpec(artery = true)

abstract class RemoteReDeploymentSlowMultiJvmSpec(artery: Boolean)
    extends RemoteReDeploymentMultiJvmSpec(new RemoteReDeploymentConfig(artery)) {
  override def sleepAfterKill = 10.seconds // new association will come in after old has been quarantined
  override def expectQuarantine = true
}

object RemoteReDeploymentMultiJvmSpec {
  class Parent extends Actor with ActorLogging {
    val monitor = context.actorSelection("/user/echo")
    log.info(s"Started Parent on path ${self.path}")
    def receive = {
      case (p: Props, n: String) => context.actorOf(p, n)
      case msg                   => monitor ! msg
    }
  }

  class Hello extends Actor with ActorLogging {
    val monitor = context.actorSelection("/user/echo")
    log.info(s"Started Hello on path ${self.path} with parent ${context.parent.path}")
    context.parent ! "HelloParent"
    override def preStart(): Unit = monitor ! "PreStart"
    override def postStop(): Unit = monitor ! "PostStop"
    def receive = Actor.emptyBehavior
  }

  class Echo(target: ActorRef) extends Actor with ActorLogging {
    def receive = {
      case msg =>
        log.info(s"received $msg from ${sender()}")
        target ! msg
    }
  }
  def echoProps(target: ActorRef) = Props(new Echo(target))
}

abstract class RemoteReDeploymentMultiJvmSpec(multiNodeConfig: RemoteReDeploymentConfig)
    extends RemotingMultiNodeSpec(multiNodeConfig) {

  def sleepAfterKill: FiniteDuration
  def expectQuarantine: Boolean

  def initialParticipants = roles.size

  import multiNodeConfig._
  import RemoteReDeploymentMultiJvmSpec._

  "A remote deployment target system" must {

    "terminate the child when its parent system is replaced by a new one" in {
      // Any message sent to `echo` will be passed on to `testActor`
      val echo = system.actorOf(echoProps(testActor), "echo")
      enterBarrier("echo-started")

      runOn(second) {
        // Create a 'Parent' actor on the 'second' node
        // have it create a 'Hello' child (which will be on the 'first' node due to the deployment config):
        system.actorOf(Props[Parent], "parent") ! ((Props[Hello], "hello"))
        // The 'Hello' child will send "HelloParent" to the 'Parent', which will pass it to the 'echo' monitor:
        expectMsg(15.seconds, "HelloParent")
      }

      runOn(first) {
        // Check the 'Hello' actor was started on the first node
        expectMsg(15.seconds, "PreStart")
      }

      enterBarrier("first-deployed")

      // Disconnect the second system from the first, and shut it down
      runOn(first) {
        testConductor.blackhole(second, first, Both).await
        testConductor.shutdown(second, abort = true).await
        if (expectQuarantine)
          within(sleepAfterKill) {
            // The quarantine of node 2, where the Parent lives, should cause the Hello child to be stopped:
            expectMsg("PostStop")
            expectNoMsg()
          } else expectNoMsg(sleepAfterKill)
        awaitAssert(node(second), 10.seconds, 100.millis)
      }

      var sys: ActorSystem = null

      // Start the second system again
      runOn(second) {
        Await.ready(system.whenTerminated, 30.seconds)
        expectNoMsg(sleepAfterKill)
        sys = startNewSystem()
      }

      enterBarrier("cable-cut")

      runOn(first) {
        testConductor.passThrough(second, first, Both).await
      }

      // make sure ordinary communication works
      runOn(second) {
        val sel = sys.actorSelection(node(first) / "user" / "echo")
        val p = TestProbe()(sys)
        p.within(15.seconds) {
          p.awaitAssert {
            sel.tell(Identify("id-echo-again"), p.ref)
            p.expectMsgType[ActorIdentity](3.seconds).ref.isDefined should ===(true)
          }
        }
      }

      enterBarrier("ready-again")

      // add new echo, parent, and (if needed) Hello actors:
      runOn(second) {
        val p = TestProbe()(sys)
        sys.actorOf(echoProps(p.ref), "echo")
        p.send(sys.actorOf(Props[Parent], "parent"), (Props[Hello], "hello"))
        p.expectMsg(15.seconds, "HelloParent")
      }

      enterBarrier("re-deployed")

      // Check the Hello actor is (re)started on node 1:
      runOn(first) {
        within(15.seconds) {
          if (expectQuarantine) expectMsg("PreStart")
          else expectMsgAllOf("PostStop", "PreStart")
        }
      }

      enterBarrier("the-end")

      // After this we expect no further messages
      expectNoMsg(1.second)

      // Until we clean up after ourselves
      enterBarrier("stopping")

      runOn(second) {
        Await.result(sys.terminate(), 10.seconds)
      }
    }

  }

}

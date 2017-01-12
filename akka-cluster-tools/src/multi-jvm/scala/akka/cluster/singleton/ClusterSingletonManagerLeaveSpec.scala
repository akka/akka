/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.singleton

import language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Address
import akka.actor.Props
import akka.actor.PoisonPill
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent._
import akka.actor.Terminated
import akka.actor.ActorSelection
import akka.cluster.MemberStatus

object ClusterSingletonManagerLeaveSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "cluster"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = off
    """))

  case object EchoStarted
  /**
   * The singleton actor
   */
  class Echo(testActor: ActorRef) extends Actor {
    override def preStart(): Unit = {
      testActor ! "preStart"
    }
    override def postStop(): Unit = {
      testActor ! "postStop"
    }

    def receive = {
      case "stop" ⇒
        testActor ! "stop"
        context.stop(self)
      case _ ⇒
        sender() ! self
    }
  }
}

class ClusterSingletonManagerLeaveMultiJvmNode1 extends ClusterSingletonManagerLeaveSpec
class ClusterSingletonManagerLeaveMultiJvmNode2 extends ClusterSingletonManagerLeaveSpec
class ClusterSingletonManagerLeaveMultiJvmNode3 extends ClusterSingletonManagerLeaveSpec

class ClusterSingletonManagerLeaveSpec extends MultiNodeSpec(ClusterSingletonManagerLeaveSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterSingletonManagerLeaveSpec._

  override def initialParticipants = roles.size

  lazy val cluster = Cluster(system)

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      cluster join node(to).address
      createSingleton()
    }
  }

  def createSingleton(): ActorRef = {
    system.actorOf(
      ClusterSingletonManager.props(
        singletonProps = Props(classOf[Echo], testActor),
        terminationMessage = "stop",
        settings = ClusterSingletonManagerSettings(system)),
      name = "echo")
  }

  lazy val echoProxy: ActorRef = {
    system.actorOf(
      ClusterSingletonProxy.props(
        singletonManagerPath = "/user/echo",
        settings = ClusterSingletonProxySettings(system)),
      name = "echoProxy")
  }

  "Leaving ClusterSingletonManager" must {

    "hand-over to new instance" in {
      join(first, first)

      runOn(first) {
        within(5.seconds) {
          expectMsg("preStart")
          echoProxy ! "hello"
          expectMsgType[ActorRef]
        }
      }
      enterBarrier("first-active")

      join(second, first)
      runOn(first, second) {
        within(10.seconds) {
          awaitAssert(cluster.state.members.count(m ⇒ m.status == MemberStatus.Up) should be(2))
        }
      }
      enterBarrier("second-up")

      join(third, first)
      within(10.seconds) {
        awaitAssert(cluster.state.members.count(m ⇒ m.status == MemberStatus.Up) should be(3))
      }
      enterBarrier("all-up")

      runOn(second) {
        cluster.leave(node(first).address)
      }

      runOn(first) {
        expectMsg(10.seconds, "stop")
        expectMsg("postStop")
      }
      enterBarrier("first-stopped")

      runOn(second) {
        expectMsg("preStart")
      }
      enterBarrier("second-started")

      runOn(second, third) {
        val p = TestProbe()
        val firstAddress = node(first).address
        p.within(15.seconds) {
          p.awaitAssert {
            echoProxy.tell("hello2", p.ref)
            p.expectMsgType[ActorRef](1.seconds).path.address should not be (firstAddress)

          }
        }
      }
      enterBarrier("second-working")

      runOn(third) {
        cluster.leave(node(second).address)
      }

      runOn(second) {
        expectMsg(15.seconds, "stop")
        expectMsg("postStop")
      }
      enterBarrier("second-stopped")

      runOn(third) {
        expectMsg("preStart")
      }
      enterBarrier("third-started")

      runOn(third) {
        cluster.leave(node(third).address)
      }

      runOn(third) {
        expectMsg(5.seconds, "stop")
        expectMsg("postStop")
      }
      enterBarrier("third-stopped")

    }

  }
}

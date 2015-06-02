/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

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
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = off
    """))

  case object EchoStarted
  /**
   * The singleton actor
   */
  class Echo(testActor: ActorRef) extends Actor {
    override def postStop(): Unit = {
      testActor ! "stopped"
    }

    def receive = {
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
    system.actorOf(ClusterSingletonManager.props(
      singletonProps = Props(classOf[Echo], testActor),
      singletonName = "echo",
      terminationMessage = PoisonPill,
      role = None),
      name = "singleton")
  }

  lazy val echoProxy: ActorRef = {
    system.actorOf(ClusterSingletonProxy.props(
      singletonPath = "/user/singleton/echo",
      role = None),
      name = "echoProxy")
  }

  "Leaving ClusterSingletonManager" must {

    "hand-over to new instance" in {
      join(first, first)

      runOn(first) {
        echoProxy ! "hello"
        expectMsgType[ActorRef](5.seconds)
      }
      enterBarrier("first-active")

      join(second, first)
      join(third, first)
      within(10.seconds) {
        awaitAssert(cluster.state.members.count(m ⇒ m.status == MemberStatus.Up) should be(3))
      }

      runOn(second) {
        cluster.leave(node(first).address)
      }

      runOn(first) {
        expectMsg(10.seconds, "stopped")
      }
      enterBarrier("first-stopped")

      runOn(second, third) {
        val p = TestProbe()
        p.within(10.seconds) {
          p.awaitAssert {
            echoProxy.tell("hello2", p.ref)
            p.expectMsgType[ActorRef](1.seconds)
          }
        }
      }

      enterBarrier("hand-over-done")
    }

  }
}

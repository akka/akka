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

object ClusterSingletonManagerStartupSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-down-unreachable-after = 0s
    """))

  case object EchoStarted
  /**
   * The singleton actor
   */
  class Echo(testActor: ActorRef) extends Actor {
    def receive = {
      case _ ⇒
        sender() ! self
    }
  }
}

class ClusterSingletonManagerStartupMultiJvmNode1 extends ClusterSingletonManagerStartupSpec
class ClusterSingletonManagerStartupMultiJvmNode2 extends ClusterSingletonManagerStartupSpec
class ClusterSingletonManagerStartupMultiJvmNode3 extends ClusterSingletonManagerStartupSpec

class ClusterSingletonManagerStartupSpec extends MultiNodeSpec(ClusterSingletonManagerStartupSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterSingletonManagerStartupSpec._

  override def initialParticipants = roles.size

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
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

  "Startup of Cluster Singleton" must {

    "be quick" in {
      join(first, first)
      join(second, first)
      join(third, first)

      runOn(first) {
        echoProxy ! "hello"
        expectMsgType[ActorRef](10.seconds)
      }

      enterBarrier("first-verified")
    }

  }
}

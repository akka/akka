/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
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

object ClusterSingletonManagerChaosSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")
  val fifth = role("fifth")
  val sixth = role("sixth")

  commonConfig(ConfigFactory.parseString("""
    akka.loglevel = INFO
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.remote.log-remote-lifecycle-events = off
    akka.cluster.auto-join = off
    akka.cluster.auto-down = on
    """))

  case object EchoStarted
  /**
   * The singleton actor
   */
  class Echo(testActor: ActorRef) extends Actor {
    testActor ! EchoStarted

    def receive = {
      case _ ⇒ sender ! self
    }
  }
}

class ClusterSingletonManagerChaosMultiJvmNode1 extends ClusterSingletonManagerChaosSpec
class ClusterSingletonManagerChaosMultiJvmNode2 extends ClusterSingletonManagerChaosSpec
class ClusterSingletonManagerChaosMultiJvmNode3 extends ClusterSingletonManagerChaosSpec
class ClusterSingletonManagerChaosMultiJvmNode4 extends ClusterSingletonManagerChaosSpec
class ClusterSingletonManagerChaosMultiJvmNode5 extends ClusterSingletonManagerChaosSpec
class ClusterSingletonManagerChaosMultiJvmNode6 extends ClusterSingletonManagerChaosSpec
class ClusterSingletonManagerChaosMultiJvmNode7 extends ClusterSingletonManagerChaosSpec

class ClusterSingletonManagerChaosSpec extends MultiNodeSpec(ClusterSingletonManagerChaosSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterSingletonManagerChaosSpec._

  override def initialParticipants = roles.size

  // Sort the roles in the order used by the cluster.
  lazy val sortedClusterRoles: immutable.IndexedSeq[RoleName] = {
    implicit val clusterOrdering: Ordering[RoleName] = new Ordering[RoleName] {
      import Member.addressOrdering
      def compare(x: RoleName, y: RoleName) =
        addressOrdering.compare(node(x).address, node(y).address)
    }
    roles.filterNot(_ == controller).toVector.sorted
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      createSingleton()
    }
  }

  def createSingleton(): ActorRef = {
    system.actorOf(Props(new ClusterSingletonManager(
      singletonProps = handOverData ⇒ Props(new Echo(testActor)),
      singletonName = "echo",
      terminationMessage = PoisonPill)),
      name = "singleton")
  }

  def crash(roles: RoleName*): Unit = {
    runOn(controller) {
      roles foreach { r ⇒
        log.info("Shutdown [{}]", node(r).address)
        testConductor.shutdown(r, 0).await
      }
    }
  }

  def echo(leader: RoleName): ActorRef =
    system.actorFor(RootActorPath(node(leader).address) / "user" / "singleton" / "echo")

  def verify(leader: RoleName): Unit = {
    enterBarrier("before-" + leader.name + "-verified")
    runOn(leader) {
      expectMsg(EchoStarted)
    }
    enterBarrier(leader.name + "-active")

    runOn(sortedClusterRoles.filterNot(_ == leader): _*) {
      echo(leader) ! "hello"
      fishForMessage() {
        case _: ActorRef ⇒ true
        case EchoStarted ⇒ false
      } match {
        case echoRef: ActorRef ⇒ echoRef.path.address must be(node(leader).address)
      }
    }
    enterBarrier(leader.name + "-verified")
  }

  "A ClusterSingletonManager in chaotic cluster" must {

    "startup 3 node cluster" in within(90 seconds) {
      log.info("Sorted cluster nodes [{}]", sortedClusterRoles.map(node(_).address).mkString(", "))

      join(sortedClusterRoles(5), sortedClusterRoles.last)
      join(sortedClusterRoles(4), sortedClusterRoles.last)
      join(sortedClusterRoles(3), sortedClusterRoles.last)

      verify(sortedClusterRoles(3))
    }

    "hand over when joining 3 more nodes" in within(90 seconds) {
      join(sortedClusterRoles(2), sortedClusterRoles(3))
      join(sortedClusterRoles(1), sortedClusterRoles(4))
      join(sortedClusterRoles(0), sortedClusterRoles(5))

      verify(sortedClusterRoles(0))
    }

    "take over when three leaders crash in 6 nodes cluster" in within(90 seconds) {
      crash(sortedClusterRoles(0), sortedClusterRoles(1), sortedClusterRoles(2))
      verify(sortedClusterRoles(3))
    }

  }
}

/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern

import language.postfixOps
import scala.collection.immutable
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Props
import akka.actor.RootActorPath
import akka.cluster.Cluster
import akka.cluster.ClusterEvent._
import akka.cluster.Member
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.testkit.ImplicitSender
import akka.testkit.TestEvent._
import akka.actor.Terminated

object ClusterSingletonManagerSpec extends MultiNodeConfig {
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

  testTransport(on = true)

  object PointToPointChannel {
    case object RegisterConsumer
    case object UnregisterConsumer
    case object RegistrationOk
    case object UnexpectedRegistration
    case object UnregistrationOk
    case object UnexpectedUnregistration
    case object Reset
    case object ResetOk
  }

  /**
   * This channel is extremly strict with regards to
   * registration and unregistration of consumer to
   * be able to detect misbehaviour (e.g. two active
   * singleton instances).
   */
  class PointToPointChannel extends Actor with ActorLogging {
    import PointToPointChannel._

    def receive = idle

    def idle: Receive = {
      case RegisterConsumer ⇒
        log.info("RegisterConsumer: [{}]", sender.path)
        sender ! RegistrationOk
        context.become(active(sender))
      case UnregisterConsumer ⇒
        log.info("UnexpectedUnregistration: [{}]", sender.path)
        sender ! UnexpectedUnregistration
        context stop self
      case Reset ⇒ sender ! ResetOk
      case msg   ⇒ // no consumer, drop
    }

    def active(consumer: ActorRef): Receive = {
      case UnregisterConsumer if sender == consumer ⇒
        log.info("UnregistrationOk: [{}]", sender.path)
        sender ! UnregistrationOk
        context.become(idle)
      case UnregisterConsumer ⇒
        log.info("UnexpectedUnregistration: [{}], expected [{}]", sender.path, consumer.path)
        sender ! UnexpectedUnregistration
        context stop self
      case RegisterConsumer ⇒
        log.info("Unexpected RegisterConsumer [{}], active consumer [{}]", sender.path, consumer.path)
        sender ! UnexpectedRegistration
        context stop self
      case Reset ⇒
        context.become(idle)
        sender ! ResetOk
      case msg ⇒ consumer ! msg
    }
  }

  object Consumer {
    case object End
    case object GetCurrent
  }

  /**
   * The Singleton actor
   */
  class Consumer(handOverData: Option[Any], queue: ActorRef, delegateTo: ActorRef) extends Actor {
    import Consumer._
    import PointToPointChannel._

    var current: Int = handOverData match {
      case Some(x: Int) ⇒ x
      case Some(x)      ⇒ throw new IllegalArgumentException(s"handOverData must be an Int, got [${x}]")
      case None         ⇒ 0
    }

    override def preStart(): Unit = queue ! RegisterConsumer

    def receive = {
      case n: Int if n <= current ⇒
        context.stop(self)
      case n: Int ⇒
        current = n
        delegateTo ! n
      case x @ (RegistrationOk | UnexpectedRegistration) ⇒
        delegateTo ! x
      case GetCurrent ⇒
        sender ! current
      //#consumer-end
      case End ⇒
        queue ! UnregisterConsumer
      case UnregistrationOk ⇒
        // reply to ClusterSingletonManager with hand over data,
        // which will be passed as parameter to new leader consumer
        context.parent ! current
        context stop self
      //#consumer-end
    }
  }

}

class ClusterSingletonManagerMultiJvmNode1 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode2 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode3 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode4 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode5 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode6 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode7 extends ClusterSingletonManagerSpec

class ClusterSingletonManagerSpec extends MultiNodeSpec(ClusterSingletonManagerSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterSingletonManagerSpec._
  import ClusterSingletonManagerSpec.PointToPointChannel._
  import ClusterSingletonManagerSpec.Consumer._

  override def initialParticipants = roles.size

  // Sort the roles in the order used by the cluster.
  lazy val sortedClusterRoles: immutable.IndexedSeq[RoleName] = {
    implicit val clusterOrdering: Ordering[RoleName] = new Ordering[RoleName] {
      import Member.addressOrdering
      def compare(x: RoleName, y: RoleName) = addressOrdering.compare(node(x).address, node(y).address)
    }
    roles.filterNot(_ == controller).toVector.sorted
  }

  def queue: ActorRef = system.actorFor(node(controller) / "user" / "queue")

  def createSingleton(): ActorRef = {
    //#create-singleton-manager
    system.actorOf(Props(new ClusterSingletonManager(
      singletonProps = handOverData ⇒
        Props(new Consumer(handOverData, queue, testActor)),
      singletonName = "consumer",
      terminationMessage = End)),
      name = "singleton")
    //#create-singleton-manager
  }

  def consumer(leader: RoleName): ActorRef = {
    // the reason for this complicated way of creating the path is to illustrate
    // in documentation how it's typically done in user land
    LeaderChanged(Some(node(leader).address)) match {
      //#singleton-actorFor
      case LeaderChanged(Some(leaderAddress)) ⇒
        val path = RootActorPath(leaderAddress) / "user" / "singleton" / "consumer"
        val consumer = system.actorFor(path)
        //#singleton-actorFor
        consumer
    }
  }

  def verify(leader: RoleName, msg: Int, expectedCurrent: Int): Unit = {
    enterBarrier("before-" + leader.name + "-verified")
    runOn(leader) {
      expectMsg(RegistrationOk)
      consumer(leader) ! GetCurrent
      expectMsg(expectedCurrent)
    }
    enterBarrier(leader.name + "-active")

    runOn(controller) {
      queue ! msg
      // make sure it's not terminated, which would be wrong
      expectNoMsg(1 second)
    }
    runOn(leader) {
      expectMsg(msg)
    }
    runOn(sortedClusterRoles.filterNot(_ == leader): _*) {
      expectNoMsg(1 second)
    }
    enterBarrier(leader.name + "-verified")
  }

  def crash(roles: RoleName*): Unit = {
    runOn(controller) {
      queue ! Reset
      expectMsg(ResetOk)
      roles foreach { r ⇒
        log.info("Shutdown [{}]", node(r).address)
        testConductor.shutdown(r, 0).await
      }
    }
  }

  "A ClusterSingletonManager" must {

    "startup in single member cluster" in within(10 seconds) {
      log.info("Sorted cluster nodes [{}]", sortedClusterRoles.map(node(_).address).mkString(", "))

      runOn(controller) {
        // watch that it is not terminated, which would indicate misbehaviour
        watch(system.actorOf(Props[PointToPointChannel], "queue"))
      }
      enterBarrier("queue-started")

      runOn(sortedClusterRoles(5)) {
        Cluster(system) join node(sortedClusterRoles(5)).address
        createSingleton()
      }

      verify(sortedClusterRoles.last, msg = 1, expectedCurrent = 0)
    }

    "hand over when new leader joins to 1 node cluster" in within(15 seconds) {
      val newLeaderRole = sortedClusterRoles(4)
      runOn(newLeaderRole) {
        Cluster(system) join node(sortedClusterRoles.last).address
        createSingleton()
      }

      verify(newLeaderRole, msg = 2, expectedCurrent = 1)
    }

    "hand over when new leader joins to 2 nodes cluster" in within(15 seconds) {
      val newLeaderRole = sortedClusterRoles(3)
      runOn(newLeaderRole) {
        Cluster(system) join node(sortedClusterRoles.last).address
        createSingleton()
      }

      verify(newLeaderRole, msg = 3, expectedCurrent = 2)
    }

    "hand over when adding three new potential leaders to 3 nodes cluster" in within(30 seconds) {
      runOn(sortedClusterRoles(2)) {
        Cluster(system) join node(sortedClusterRoles(3)).address
        createSingleton()
      }
      runOn(sortedClusterRoles(1)) {
        Cluster(system) join node(sortedClusterRoles(4)).address
        createSingleton()
      }
      runOn(sortedClusterRoles(0)) {
        Cluster(system) join node(sortedClusterRoles(5)).address
        createSingleton()
      }

      verify(sortedClusterRoles(0), msg = 4, expectedCurrent = 3)
    }

    "hand over when leader leaves in 6 nodes cluster " in within(20 seconds) {
      val leaveRole = sortedClusterRoles(0)
      val newLeaderRole = sortedClusterRoles(1)

      runOn(leaveRole) {
        Cluster(system) leave node(leaveRole).address
      }

      verify(newLeaderRole, msg = 5, expectedCurrent = 4)

      runOn(leaveRole) {
        val singleton = system.actorFor("/user/singleton")
        watch(singleton)
        expectMsgType[Terminated].actor must be(singleton)
      }

      enterBarrier("after-leave")
    }

    "take over when leader crashes in 5 nodes cluster" in within(35 seconds) {
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*")))
      system.eventStream.publish(Mute(EventFilter.error(pattern = ".*Disassociated.*")))
      system.eventStream.publish(Mute(EventFilter.error(pattern = ".*Association failed.*")))
      enterBarrier("logs-muted")

      crash(sortedClusterRoles(1))
      verify(sortedClusterRoles(2), msg = 6, expectedCurrent = 0)
    }

    "take over when two leaders crash in 3 nodes cluster" in within(45 seconds) {
      crash(sortedClusterRoles(2), sortedClusterRoles(3))
      verify(sortedClusterRoles(4), msg = 7, expectedCurrent = 0)
    }

    "take over when leader crashes in 2 nodes cluster" in within(25 seconds) {
      crash(sortedClusterRoles(4))
      verify(sortedClusterRoles(5), msg = 6, expectedCurrent = 0)
    }

  }
}

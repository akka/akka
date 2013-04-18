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
import akka.actor.Identify
import akka.actor.ActorIdentity
import akka.actor.ActorSelection

object ClusterSingletonManagerSpec extends MultiNodeConfig {
  val controller = role("controller")
  val observer = role("observer")
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

  nodeConfig(first, second, third, fourth, fifth, sixth)(
    ConfigFactory.parseString("akka.cluster.roles =[worker]"))

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

  // documentation of how to keep track of the leader address in user land
  //#singleton-proxy
  class ConsumerProxy extends Actor {
    // subscribe to LeaderChanged, re-subscribe when restart
    override def preStart(): Unit =
      Cluster(context.system).subscribe(self, classOf[LeaderChanged])
    override def postStop(): Unit =
      Cluster(context.system).unsubscribe(self)

    var leaderAddress: Option[Address] = None

    def receive = {
      case state: CurrentClusterState ⇒ leaderAddress = state.leader
      case LeaderChanged(leader)      ⇒ leaderAddress = leader
      case other                      ⇒ consumer foreach { _.tell(other, sender) }
    }

    def consumer: Option[ActorSelection] =
      leaderAddress map (a ⇒ context.actorSelection(RootActorPath(a) /
        "user" / "singleton" / "consumer"))
  }
  //#singleton-proxy

  // documentation of how to keep track of the role leader address in user land
  //#singleton-proxy2
  class ConsumerProxy2 extends Actor {
    // subscribe to RoleLeaderChanged, re-subscribe when restart
    override def preStart(): Unit =
      Cluster(context.system).subscribe(self, classOf[RoleLeaderChanged])
    override def postStop(): Unit =
      Cluster(context.system).unsubscribe(self)

    val role = "worker"
    var leaderAddress: Option[Address] = None

    def receive = {
      case state: CurrentClusterState   ⇒ leaderAddress = state.roleLeader(role)
      case RoleLeaderChanged(r, leader) ⇒ if (r == role) leaderAddress = leader
      case other                        ⇒ consumer foreach { _.tell(other, sender) }
    }

    def consumer: Option[ActorSelection] =
      leaderAddress map (a ⇒ context.actorSelection(RootActorPath(a) /
        "user" / "singleton" / "consumer"))
  }
  //#singleton-proxy2

}

class ClusterSingletonManagerMultiJvmNode1 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode2 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode3 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode4 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode5 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode6 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode7 extends ClusterSingletonManagerSpec
class ClusterSingletonManagerMultiJvmNode8 extends ClusterSingletonManagerSpec

class ClusterSingletonManagerSpec extends MultiNodeSpec(ClusterSingletonManagerSpec) with STMultiNodeSpec with ImplicitSender {
  import ClusterSingletonManagerSpec._
  import ClusterSingletonManagerSpec.PointToPointChannel._
  import ClusterSingletonManagerSpec.Consumer._

  override def initialParticipants = roles.size

  val identifyProbe = TestProbe()

  //#sort-cluster-roles
  // Sort the roles in the order used by the cluster.
  lazy val sortedWorkerNodes: immutable.IndexedSeq[RoleName] = {
    implicit val clusterOrdering: Ordering[RoleName] = new Ordering[RoleName] {
      import Member.addressOrdering
      def compare(x: RoleName, y: RoleName) =
        addressOrdering.compare(node(x).address, node(y).address)
    }
    roles.filterNot(r ⇒ r == controller || r == observer).toVector.sorted
  }
  //#sort-cluster-roles

  def queue: ActorRef = {
    system.actorSelection(node(controller) / "user" / "queue").tell(Identify("queue"), identifyProbe.ref)
    identifyProbe.expectMsgType[ActorIdentity].ref.get
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      if (Cluster(system).selfRoles.contains("worker")) createSingleton()
    }
  }

  def createSingleton(): ActorRef = {
    //#create-singleton-manager
    system.actorOf(ClusterSingletonManager.props(
      singletonProps = handOverData ⇒
        Props(classOf[Consumer], handOverData, queue, testActor),
      singletonName = "consumer",
      terminationMessage = End,
      role = Some("worker")),
      name = "singleton")
    //#create-singleton-manager
  }

  def consumer(leader: RoleName): ActorSelection =
    system.actorSelection(RootActorPath(node(leader).address) / "user" / "singleton" / "consumer")

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
    runOn(sortedWorkerNodes.filterNot(_ == leader): _*) {
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
      log.info("Sorted cluster nodes [{}]", sortedWorkerNodes.map(node(_).address).mkString(", "))

      runOn(controller) {
        // watch that it is not terminated, which would indicate misbehaviour
        watch(system.actorOf(Props[PointToPointChannel], "queue"))
      }
      enterBarrier("queue-started")

      join(sortedWorkerNodes.last, sortedWorkerNodes.last)
      verify(sortedWorkerNodes.last, msg = 1, expectedCurrent = 0)

      // join the observer node as well, which should not influence since it doesn't have the "worker" role
      join(observer, sortedWorkerNodes.last)
      enterBarrier("after-1")
    }

    "hand over when new leader joins to 1 node cluster" in within(15 seconds) {
      val newLeaderRole = sortedWorkerNodes(4)
      join(newLeaderRole, sortedWorkerNodes.last)
      verify(newLeaderRole, msg = 2, expectedCurrent = 1)
    }

    "hand over when new leader joins to 2 nodes cluster" in within(15 seconds) {
      val newLeaderRole = sortedWorkerNodes(3)
      join(newLeaderRole, sortedWorkerNodes.last)
      verify(newLeaderRole, msg = 3, expectedCurrent = 2)
    }

    "hand over when new leader joins to 3 nodes cluster" in within(15 seconds) {
      val newLeaderRole = sortedWorkerNodes(2)
      join(newLeaderRole, sortedWorkerNodes.last)
      verify(newLeaderRole, msg = 4, expectedCurrent = 3)
    }

    "hand over when new leader joins to 4 nodes cluster" in within(15 seconds) {
      val newLeaderRole = sortedWorkerNodes(1)
      join(newLeaderRole, sortedWorkerNodes.last)
      verify(newLeaderRole, msg = 5, expectedCurrent = 4)
    }

    "hand over when new leader joins to 5 nodes cluster" in within(15 seconds) {
      val newLeaderRole = sortedWorkerNodes(0)
      join(newLeaderRole, sortedWorkerNodes.last)
      verify(newLeaderRole, msg = 6, expectedCurrent = 5)
    }

    "hand over when leader leaves in 6 nodes cluster " in within(30 seconds) {
      //#test-leave
      val leaveRole = sortedWorkerNodes(0)
      val newLeaderRole = sortedWorkerNodes(1)

      runOn(leaveRole) {
        Cluster(system) leave node(leaveRole).address
      }
      //#test-leave

      verify(newLeaderRole, msg = 7, expectedCurrent = 6)

      runOn(leaveRole) {
        system.actorSelection("/user/singleton").tell(Identify("singleton"), identifyProbe.ref)
        identifyProbe.expectMsgPF() {
          case ActorIdentity("singleton", None) ⇒ // already terminated
          case ActorIdentity("singleton", Some(singleton)) ⇒
            watch(singleton)
            expectTerminated(singleton)
        }
      }

      enterBarrier("after-leave")
    }

    "take over when leader crashes in 5 nodes cluster" in within(60 seconds) {
      system.eventStream.publish(Mute(EventFilter.warning(pattern = ".*received dead letter from.*")))
      system.eventStream.publish(Mute(EventFilter.error(pattern = ".*Disassociated.*")))
      system.eventStream.publish(Mute(EventFilter.error(pattern = ".*Association failed.*")))
      enterBarrier("logs-muted")

      crash(sortedWorkerNodes(1))
      verify(sortedWorkerNodes(2), msg = 8, expectedCurrent = 0)
    }

    "take over when two leaders crash in 3 nodes cluster" in within(60 seconds) {
      crash(sortedWorkerNodes(2), sortedWorkerNodes(3))
      verify(sortedWorkerNodes(4), msg = 9, expectedCurrent = 0)
    }

    "take over when leader crashes in 2 nodes cluster" in within(60 seconds) {
      crash(sortedWorkerNodes(4))
      verify(sortedWorkerNodes(5), msg = 10, expectedCurrent = 0)
    }

  }
}

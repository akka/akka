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
        // which will be passed as parameter to new consumer singleton
        context.parent ! current
        context stop self
      //#consumer-end
    }
  }

  // documentation of how to keep track of the oldest member in user land
  //#singleton-proxy
  class ConsumerProxy extends Actor {
    // subscribe to MemberEvent, re-subscribe when restart
    override def preStart(): Unit =
      Cluster(context.system).subscribe(self, classOf[MemberEvent])
    override def postStop(): Unit =
      Cluster(context.system).unsubscribe(self)

    val role = "worker"
    // sort by age, oldest first
    val ageOrdering = Ordering.fromLessThan[Member] { (a, b) ⇒ a.isOlderThan(b) }
    var membersByAge: immutable.SortedSet[Member] = immutable.SortedSet.empty(ageOrdering)

    def receive = {
      case state: CurrentClusterState ⇒
        membersByAge = immutable.SortedSet.empty(ageOrdering) ++ state.members.collect {
          case m if m.hasRole(role) ⇒ m
        }
      case MemberUp(m)         ⇒ if (m.hasRole(role)) membersByAge += m
      case MemberRemoved(m, _) ⇒ if (m.hasRole(role)) membersByAge -= m
      case other               ⇒ consumer foreach { _.tell(other, sender) }
    }

    def consumer: Option[ActorSelection] =
      membersByAge.headOption map (m ⇒ context.actorSelection(RootActorPath(m.address) /
        "user" / "singleton" / "consumer"))
  }
  //#singleton-proxy

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

  val controllerRootActorPath = node(controller)

  def queue: ActorRef = {
    // this is used from inside actor construction, i.e. other thread, and must therefore not call `node(controller`
    system.actorSelection(controllerRootActorPath / "user" / "queue").tell(Identify("queue"), identifyProbe.ref)
    identifyProbe.expectMsgType[ActorIdentity].ref.get
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      if (Cluster(system).selfRoles.contains("worker")) createSingleton()
    }
  }

  def awaitMemberUp(memberProbe: TestProbe, nodes: RoleName*): Unit = {
    runOn(nodes.filterNot(_ == nodes.head): _*) {
      memberProbe.expectMsgType[MemberUp](15.seconds).member.address must be(node(nodes.head).address)
    }
    runOn(nodes.head) {
      memberProbe.receiveN(nodes.size, 15.seconds).collect { case MemberUp(m) ⇒ m.address }.toSet must be(
        nodes.map(node(_).address).toSet)
    }
    enterBarrier(nodes.head.name + "-up")
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

  def consumer(oldest: RoleName): ActorSelection =
    system.actorSelection(RootActorPath(node(oldest).address) / "user" / "singleton" / "consumer")

  def verifyRegistration(oldest: RoleName, expectedCurrent: Int): Unit = {
    enterBarrier("before-" + oldest.name + "-registration-verified")
    runOn(oldest) {
      expectMsg(RegistrationOk)
      consumer(oldest) ! GetCurrent
      expectMsg(expectedCurrent)
    }
    enterBarrier("after-" + oldest.name + "-registration-verified")
  }

  def verifyMsg(oldest: RoleName, msg: Int): Unit = {
    enterBarrier("before-" + msg + "-verified")

    runOn(controller) {
      queue ! msg
      // make sure it's not terminated, which would be wrong
      expectNoMsg(1 second)
    }
    runOn(oldest) {
      expectMsg(5.seconds, msg)
    }
    runOn(roles.filterNot(r ⇒ r == oldest || r == controller || r == observer): _*) {
      expectNoMsg(1 second)
    }
    enterBarrier("after-" + msg + "-verified")
  }

  def crash(roles: RoleName*): Unit = {
    runOn(controller) {
      queue ! Reset
      expectMsg(ResetOk)
      roles foreach { r ⇒
        log.info("Shutdown [{}]", node(r).address)
        testConductor.exit(r, 0).await
      }
    }
  }

  "A ClusterSingletonManager" must {

    "startup 6 node cluster" in within(60 seconds) {

      val memberProbe = TestProbe()
      Cluster(system).subscribe(memberProbe.ref, classOf[MemberUp])
      memberProbe.expectMsgClass(classOf[CurrentClusterState])

      runOn(controller) {
        // watch that it is not terminated, which would indicate misbehaviour
        watch(system.actorOf(Props[PointToPointChannel], "queue"))
      }
      enterBarrier("queue-started")

      join(first, first)
      awaitMemberUp(memberProbe, first)
      verifyRegistration(first, expectedCurrent = 0)
      verifyMsg(first, msg = 1)

      // join the observer node as well, which should not influence since it doesn't have the "worker" role
      join(observer, first)
      awaitMemberUp(memberProbe, observer, first)

      join(second, first)
      awaitMemberUp(memberProbe, second, observer, first)
      verifyMsg(first, msg = 2)

      join(third, first)
      awaitMemberUp(memberProbe, third, second, observer, first)
      verifyMsg(first, msg = 3)

      join(fourth, first)
      awaitMemberUp(memberProbe, fourth, third, second, observer, first)
      verifyMsg(first, msg = 4)

      join(fifth, first)
      awaitMemberUp(memberProbe, fifth, fourth, third, second, observer, first)
      verifyMsg(first, msg = 5)

      join(sixth, first)
      awaitMemberUp(memberProbe, sixth, fifth, fourth, third, second, observer, first)
      verifyMsg(first, msg = 6)

      enterBarrier("after-1")
    }

    "hand over when oldest leaves in 6 nodes cluster " in within(30 seconds) {
      val leaveRole = first
      val newOldestRole = second

      runOn(leaveRole) {
        Cluster(system) leave node(leaveRole).address
      }

      verifyRegistration(second, expectedCurrent = 6)
      verifyMsg(second, msg = 7)

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

    "take over when oldest crashes in 5 nodes cluster" in within(60 seconds) {
      // mute logging of deadLetters during shutdown of systems
      if (!log.isDebugEnabled)
        system.eventStream.publish(Mute(DeadLettersFilter[Any]))
      enterBarrier("logs-muted")

      crash(second)
      verifyRegistration(third, expectedCurrent = 0)
      verifyMsg(third, msg = 8)
    }

    "take over when two oldest crash in 3 nodes cluster" in within(60 seconds) {
      crash(third, fourth)
      verifyRegistration(fifth, expectedCurrent = 0)
      verifyMsg(fifth, msg = 9)
    }

    "take over when oldest crashes in 2 nodes cluster" in within(60 seconds) {
      crash(fifth)
      verifyRegistration(sixth, expectedCurrent = 0)
      verifyMsg(sixth, msg = 10)
    }

  }
}

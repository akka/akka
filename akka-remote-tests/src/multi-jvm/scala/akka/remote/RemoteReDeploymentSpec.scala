package akka.remote

import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit.ImplicitSender
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.remote.transport.ThrottlerTransportAdapter.Direction._
import com.typesafe.config.ConfigFactory
import akka.actor.ActorSystem
import scala.concurrent.duration._
import akka.actor.ActorLogging

object RemoteReDeploymentMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(
    """akka.remote.transport-failure-detector {
         threshold=0.1
         heartbeat-interval=0.1s
         acceptable-heartbeat-pause=1s
       }
       akka.remote.watch-failure-detector {
         threshold=0.1
         heartbeat-interval=0.1s
         acceptable-heartbeat-pause=2.5s
       }""")))
  testTransport(on = true)

  deployOn(second, "/parent/hello.remote = \"@first@\"")

  class Parent extends Actor {
    val monitor = context.actorSelection("/user/echo")
    def receive = {
      case (p: Props, n: String) ⇒ context.actorOf(p, n)
      case msg                   ⇒ monitor ! msg
    }
  }

  class Hello extends Actor {
    val monitor = context.actorSelection("/user/echo")
    context.parent ! "HelloParent"
    override def preStart(): Unit = monitor ! "PreStart"
    override def postStop(): Unit = monitor ! "PostStop"
    def receive = Actor.emptyBehavior
  }

  class Echo(target: ActorRef) extends Actor with ActorLogging {
    def receive = {
      case msg ⇒
        log.info(s"received $msg from $sender")
        target ! msg
    }
  }
  def echoProps(target: ActorRef) = Props(new Echo(target))
}

class RemoteReDeploymentFastMultiJvmNode1 extends RemoteReDeploymentFastMultiJvmSpec
class RemoteReDeploymentFastMultiJvmNode2 extends RemoteReDeploymentFastMultiJvmSpec
class RemoteReDeploymentFastMultiJvmNode3 extends RemoteReDeploymentFastMultiJvmSpec
abstract class RemoteReDeploymentFastMultiJvmSpec extends RemoteReDeploymentMultiJvmSpec {
  override def sleepAfterKill = 0.seconds // new association will come in while old is still “healthy”
  override def expectQuarantine = false
}

class RemoteReDeploymentMediumMultiJvmNode1 extends RemoteReDeploymentMediumMultiJvmSpec
class RemoteReDeploymentMediumMultiJvmNode2 extends RemoteReDeploymentMediumMultiJvmSpec
class RemoteReDeploymentMediumMultiJvmNode3 extends RemoteReDeploymentMediumMultiJvmSpec
abstract class RemoteReDeploymentMediumMultiJvmSpec extends RemoteReDeploymentMultiJvmSpec {
  override def sleepAfterKill = 1.seconds // new association will come in while old is gated in ReliableDeliverySupervisor
  override def expectQuarantine = false
}

class RemoteReDeploymentSlowMultiJvmNode1 extends RemoteReDeploymentSlowMultiJvmSpec
class RemoteReDeploymentSlowMultiJvmNode2 extends RemoteReDeploymentSlowMultiJvmSpec
class RemoteReDeploymentSlowMultiJvmNode3 extends RemoteReDeploymentSlowMultiJvmSpec
abstract class RemoteReDeploymentSlowMultiJvmSpec extends RemoteReDeploymentMultiJvmSpec {
  override def sleepAfterKill = 4.seconds // new association will come in after old has been quarantined
  override def expectQuarantine = true
}

abstract class RemoteReDeploymentMultiJvmSpec extends MultiNodeSpec(RemoteReDeploymentMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender {

  def sleepAfterKill: FiniteDuration
  def expectQuarantine: Boolean

  def initialParticipants = roles.size

  import RemoteReDeploymentMultiJvmSpec._

  "A remote deployment target system" must {

    "terminate the child when its parent system is replaced by a new one" in {

      val echo = system.actorOf(echoProps(testActor), "echo")
      val address = node(second).address

      runOn(second) {
        system.actorOf(Props[Parent], "parent") ! ((Props[Hello], "hello"))
        expectMsg("HelloParent")
      }

      runOn(first) {
        expectMsg("PreStart")
      }

      enterBarrier("first-deployed")

      runOn(first) {
        testConductor.blackhole(second, first, Both).await
        testConductor.exit(second, 0).await
        if (expectQuarantine)
          within(sleepAfterKill) {
            expectMsg("PostStop")
            expectNoMsg()
          }
        else expectNoMsg(sleepAfterKill)
      }

      enterBarrier("cable-cut")

      runOn(third) {
        val config = ConfigFactory.parseString(s"akka.remote.netty.tcp{port=${address.port.get}\nhostname=${address.host.get}}")
          .withFallback(system.settings.config)
        val sys = ActorSystem(system.name, config)
        injectDeployments(sys, second)
        sys.actorOf(echoProps(testActor), "echo")
        sys.actorOf(Props[Parent], "parent") ! ((Props[Hello], "hello"))
        expectMsg("HelloParent")
      }

      enterBarrier("re-deployed")

      runOn(first) {
        if (expectQuarantine) expectMsg("PreStart")
        else expectMsgAllOf("PostStop", "PreStart")
      }

      enterBarrier("the-end")

      expectNoMsg(1.second)

    }

  }

}
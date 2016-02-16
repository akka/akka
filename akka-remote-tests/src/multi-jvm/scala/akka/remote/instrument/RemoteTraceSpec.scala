/**
 *  Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.instrument

import akka.actor._
import akka.instrument.Trace
import akka.remote.testconductor.RoleName
import akka.remote.testkit.{ STMultiNodeSpec, MultiNodeConfig, MultiNodeSpec }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._

/**
 * RemoteTraceInstrumentation on both sides
 */
object RemoteTraceSpec extends MultiNodeConfig {
  import RemoteTraceCommon._

  val one = role("one")
  val two = role("two")

  commonConfig(debugConfig(on = false).withFallback(instrumentationConfig))
}

class RemoteTraceMultiJvmNode1 extends RemoteTraceSpec
class RemoteTraceMultiJvmNode2 extends RemoteTraceSpec

class RemoteTraceSpec extends MultiNodeSpec(RemoteTraceSpec) with RemoteTraceCommon

object RemoteTraceCommon {
  val instrumentationConfig = ConfigFactory.parseString("""
      akka.instrumentations = ["akka.remote.instrument.RemoteTraceInstrumentation"]
      akka.remote.log-remote-lifecycle-events = off
    """)

  case class Message(next: Seq[ActorRef], ids: Seq[String] = Seq.empty)

  class TraceActor extends Actor {
    def receive = {
      case Message(next, ids) ⇒ next.head ! Message(next.tail, ids :+ Trace.value.identifier)
    }
  }
}

trait RemoteTraceCommon extends STMultiNodeSpec with ImplicitSender with DefaultTimeout { this: MultiNodeSpec ⇒
  import RemoteTraceCommon._
  import RemoteTraceSpec.one
  import RemoteTraceSpec.two

  def initialParticipants = roles.size

  def actorName(role: RoleName) = s"actor-${role.name}"

  def identify(role: RoleName): ActorRef = within(10.seconds) {
    system.actorSelection(node(role) / "user" / actorName(role)) ! Identify(actorName(role))
    expectMsgType[ActorIdentity].ref.get
  }

  "RemoteInstrumentation" must {
    "transfer context in remote message flows" taggedAs LongRunningTest in {
      system.actorOf(Props[TraceActor], actorName(myself))
      enterBarrier("started")

      runOn(one) {
        val actorOne = identify(one)
        val actorTwo = identify(two)

        Trace.withValue("42") {
          actorOne ! Message(Seq(actorTwo, actorOne, testActor))
        }

        expectMsg(Message(Seq.empty, Seq(
          "42 <- actor-one",
          "42 <- actor-one <- actor-two",
          "42 <- actor-one <- actor-two <- actor-one")))
      }

      enterBarrier("done")
    }
  }
}

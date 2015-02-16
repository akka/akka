/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import akka.remote.transport.AssociationHandle

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.{ ForceDisassociateExplicitly, ForceDisassociate, Direction }
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import akka.actor.Identify
import scala.concurrent.Await

object RemoteGatePiercingSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = INFO
      akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 5 s
                              """)))

  nodeConfig(first)(
    ConfigFactory.parseString("akka.remote.retry-gate-closed-for  = 1 d # Keep it long"))

  nodeConfig(second)(
    ConfigFactory.parseString("akka.remote.retry-gate-closed-for  = 1 s # Keep it short"))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "shutdown" ⇒ context.system.shutdown()
    }
  }

}

class RemoteGatePiercingSpecMultiJvmNode1 extends RemoteGatePiercingSpec
class RemoteGatePiercingSpecMultiJvmNode2 extends RemoteGatePiercingSpec

abstract class RemoteGatePiercingSpec
  extends MultiNodeSpec(RemoteGatePiercingSpec)
  with STMultiNodeSpec with ImplicitSender {

  import RemoteGatePiercingSpec._

  override def initialParticipants = 2

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  "RemoteNodeRestartGate" must {

    "allow restarted node to pass through gate" taggedAs LongRunningTest in {
      runOn(first) {
        system.actorOf(Props[Subject], "subject")

        identify(second, "subject")

        enterBarrier("actors-communicate")

        EventFilter.warning(pattern = "address is now gated", occurrences = 1).intercept {
          Await.result(RARP(system).provider.transport.managementCommand(
            ForceDisassociateExplicitly(node(second).address, AssociationHandle.Unknown)), 3.seconds)
        }

        enterBarrier("gated")

        enterBarrier("gate-pierced")

      }

      runOn(second) {
        system.actorOf(Props[Subject], "subject")

        enterBarrier("actors-communicate")

        enterBarrier("gated")

        // Pierce the gate
        within(30.seconds) {
          awaitAssert {
            identify(first, "subject")
          }
        }

        enterBarrier("gate-pierced")

      }

    }

  }
}

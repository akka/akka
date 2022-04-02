/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.classic

import scala.concurrent.Await
import scala.concurrent.duration._

import scala.annotation.nowarn
import com.typesafe.config.ConfigFactory

import akka.actor.{ ActorIdentity, Identify, _ }
import akka.remote.{ RARP, RemotingMultiNodeSpec }
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.transport.AssociationHandle
import akka.remote.transport.ThrottlerTransportAdapter.ForceDisassociateExplicitly
import akka.testkit._

object RemoteNodeRestartGateSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
      akka.remote.artery.enabled = off
      akka.loglevel = INFO
      akka.remote.classic.log-remote-lifecycle-events = INFO
      akka.remote.classic.retry-gate-closed-for  = 1d # Keep it long
                              """)))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "shutdown" => context.system.terminate()
      case msg        => sender() ! msg
    }
  }

}

class RemoteNodeRestartGateSpecMultiJvmNode1 extends RemoteNodeRestartGateSpec
class RemoteNodeRestartGateSpecMultiJvmNode2 extends RemoteNodeRestartGateSpec

@nowarn("msg=deprecated")
abstract class RemoteNodeRestartGateSpec extends RemotingMultiNodeSpec(RemoteNodeRestartGateSpec) {

  import RemoteNodeRestartGateSpec._

  override def initialParticipants = 2

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  "RemoteNodeRestartGate" must {

    "allow restarted node to pass through gate" taggedAs LongRunningTest in {

      system.actorOf(Props[Subject](), "subject")
      enterBarrier("subject-started")

      runOn(first) {
        val secondAddress = node(second).address

        identify(second, "subject")

        EventFilter.warning(pattern = "address is now gated", occurrences = 1).intercept {
          Await.result(
            RARP(system).provider.transport
              .managementCommand(ForceDisassociateExplicitly(node(second).address, AssociationHandle.Unknown)),
            3.seconds)
        }

        enterBarrier("gated")

        testConductor.shutdown(second).await

        within(10.seconds) {
          awaitAssert {
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! Identify("subject")
            expectMsgType[ActorIdentity].ref.get
          }
        }

        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "shutdown"
      }

      runOn(second) {
        val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val firstAddress = node(first).address

        enterBarrier("gated")

        Await.ready(system.whenTerminated, 10.seconds)

        val freshSystem = ActorSystem(
          system.name,
          ConfigFactory.parseString(s"""
                    akka.remote.retry-gate-closed-for = 0.5 s
                    akka.remote.classic.netty.tcp {
                      hostname = ${address.host.get}
                      port = ${address.port.get}
                    }
                    """).withFallback(system.settings.config))

        val probe = TestProbe()(freshSystem)

        // Pierce the gate
        within(30.seconds) {
          awaitAssert {
            freshSystem
              .actorSelection(RootActorPath(firstAddress) / "user" / "subject")
              .tell(Identify("subject"), probe.ref)
            probe.expectMsgType[ActorIdentity].ref.get
          }
        }

        // Now the other system will be able to pass, too
        freshSystem.actorOf(Props[Subject](), "subject")

        Await.ready(freshSystem.whenTerminated, 30.seconds)
      }

    }

  }
}

/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.remote.transport.AssociationHandle

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.{ Direction, ForceDisassociate, ForceDisassociateExplicitly }
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import akka.actor.Identify

import scala.concurrent.Await
import akka.remote.{ AddressUidExtension, RemotingMultiNodeSpec, RARP, ThisActorSystemQuarantinedEvent }

object RemoteRestartedQuarantinedSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = WARNING
      akka.remote.log-remote-lifecycle-events = WARNING
      akka.remote.artery.enabled = on
      """)).withFallback(RemotingMultiNodeSpec.commonConfig))

  class Subject extends Actor {
    def receive = {
      case "shutdown" ⇒ context.system.terminate()
      case "identify" ⇒ sender() ! (AddressUidExtension(context.system).longAddressUid → self)
    }
  }

}

class RemoteRestartedQuarantinedSpecMultiJvmNode1 extends RemoteRestartedQuarantinedSpec
class RemoteRestartedQuarantinedSpecMultiJvmNode2 extends RemoteRestartedQuarantinedSpec

abstract class RemoteRestartedQuarantinedSpec extends RemotingMultiNodeSpec(RemoteRestartedQuarantinedSpec) {

  import RemoteRestartedQuarantinedSpec._

  override def initialParticipants = 2

  def identifyWithUid(role: RoleName, actorName: String, timeout: FiniteDuration = remainingOrDefault): (Long, ActorRef) = {
    within(timeout) {
      system.actorSelection(node(role) / "user" / actorName) ! "identify"
      expectMsgType[(Long, ActorRef)]
    }
  }

  "A restarted quarantined system" must {

    "should not crash the other system (#17213)" taggedAs LongRunningTest in {

      system.actorOf(Props[Subject], "subject")
      enterBarrier("subject-started")

      runOn(first) {
        val secondAddress = node(second).address

        val (uid, ref) = identifyWithUid(second, "subject", 5.seconds)

        enterBarrier("before-quarantined")
        RARP(system).provider.transport.quarantine(node(second).address, Some(uid), "test")

        enterBarrier("quarantined")
        enterBarrier("still-quarantined")

        testConductor.shutdown(second).await

        within(30.seconds) {
          awaitAssert {
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! Identify("subject")
            expectMsgType[ActorIdentity](1.second).ref.get
          }
        }

        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "shutdown"
      }

      runOn(second) {
        val addr = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        val firstAddress = node(first).address
        system.eventStream.subscribe(testActor, classOf[ThisActorSystemQuarantinedEvent])

        val (firstUid, ref) = identifyWithUid(first, "subject", 5.seconds)

        enterBarrier("before-quarantined")
        enterBarrier("quarantined")

        expectMsgPF(10 seconds) {
          case ThisActorSystemQuarantinedEvent(local, remote) ⇒
        }

        // check that we quarantine back
        val firstAssociation = RARP(system).provider.transport.asInstanceOf[ArteryTransport].association(firstAddress)
        awaitAssert {
          firstAssociation.associationState.isQuarantined(firstUid)
          firstAssociation.associationState.isQuarantined()
        }

        enterBarrier("still-quarantined")

        Await.result(system.whenTerminated, 10.seconds)

        val freshSystem = ActorSystem(system.name, ConfigFactory.parseString(s"""
              akka.remote.artery.canonical.port = ${addr.port.get}
              """).withFallback(system.settings.config))

        val probe = TestProbe()(freshSystem)

        freshSystem.actorSelection(RootActorPath(firstAddress) / "user" / "subject").tell(Identify("subject"), probe.ref)
        probe.expectMsgType[ActorIdentity](5.seconds).ref should not be (None)

        // Now the other system will be able to pass, too
        freshSystem.actorOf(Props[Subject], "subject")

        Await.ready(freshSystem.whenTerminated, 10.seconds)
      }

    }

  }
}

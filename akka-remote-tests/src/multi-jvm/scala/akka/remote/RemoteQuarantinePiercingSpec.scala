/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.testkit._
import akka.remote.testconductor.RoleName
import scala.concurrent.Await

class RemoteQuarantinePiercingConfig(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = INFO
      akka.remote.artery.enabled = $artery
      """)).withFallback(RemotingMultiNodeSpec.commonConfig))

}

class RemoteQuarantinePiercingMultiJvmNode1
    extends RemoteQuarantinePiercingSpec(new RemoteQuarantinePiercingConfig(artery = false))
class RemoteQuarantinePiercingMultiJvmNode2
    extends RemoteQuarantinePiercingSpec(new RemoteQuarantinePiercingConfig(artery = false))

class ArteryRemoteQuarantinePiercingMultiJvmNode1
    extends RemoteQuarantinePiercingSpec(new RemoteQuarantinePiercingConfig(artery = true))
class ArteryRemoteQuarantinePiercingMultiJvmNode2
    extends RemoteQuarantinePiercingSpec(new RemoteQuarantinePiercingConfig(artery = true))

object RemoteQuarantinePiercingSpec {
  class Subject extends Actor {
    def receive = {
      case "shutdown" => context.system.terminate()
      case "identify" => sender() ! (AddressUidExtension(context.system).longAddressUid -> self)
    }
  }
}

abstract class RemoteQuarantinePiercingSpec(multiNodeConfig: RemoteQuarantinePiercingConfig)
    extends RemotingMultiNodeSpec(multiNodeConfig) {
  import multiNodeConfig._
  import RemoteQuarantinePiercingSpec._

  override def initialParticipants = roles.size

  def identifyWithUid(
      role: RoleName,
      actorName: String,
      timeout: FiniteDuration = remainingOrDefault): (Long, ActorRef) = {
    within(timeout) {
      system.actorSelection(node(role) / "user" / actorName) ! "identify"
      expectMsgType[(Long, ActorRef)]
    }
  }

  "RemoteNodeShutdownAndComesBack" must {

    "allow piercing through the quarantine when remote UID is new" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        enterBarrier("actors-started")

        // Acquire ActorRef from first system
        val (uidFirst, subjectFirst) = identifyWithUid(second, "subject", 5.seconds)
        enterBarrier("actor-identified")

        // Manually Quarantine the other system
        RARP(system).provider.transport.quarantine(node(second).address, Some(uidFirst), "test")

        // Quarantine is up -- Cannot communicate with remote system any more
        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "identify"
        expectNoMsg(2.seconds)

        // Shut down the other system -- which results in restart (see runOn(second))
        Await.result(testConductor.shutdown(second), 30.seconds)

        // Now wait until second system becomes alive again
        within(30.seconds) {
          // retry because the Subject actor might not be started yet
          awaitAssert {
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "identify"
            val (uidSecond, subjectSecond) = expectMsgType[(Long, ActorRef)](1.second)
            uidSecond should not be (uidFirst)
            subjectSecond should not be (subjectFirst)
          }
        }

        // If we got here the Quarantine was successfully pierced since it is configured to last 1 day

        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "shutdown"

      }

      runOn(second) {
        val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")

        enterBarrier("actor-identified")

        Await.ready(system.whenTerminated, 30.seconds)

        val freshSystem = ActorSystem(
          system.name,
          ConfigFactory.parseString(s"""
          akka.remote.netty.tcp.port = ${address.port.get}
          akka.remote.artery.canonical.port = ${address.port.get}
          """).withFallback(system.settings.config))
        freshSystem.actorOf(Props[Subject], "subject")

        Await.ready(freshSystem.whenTerminated, 30.seconds)
      }

    }

  }
}

/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.{ ForceDisassociate, Direction }
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import akka.actor.Identify
import scala.concurrent.Await

object RemoteQuarantinePiercingSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = INFO
                              """)))

  class Subject extends Actor {
    def receive = {
      case "shutdown" ⇒ context.system.terminate()
      case "identify" ⇒ sender() ! (AddressUidExtension(context.system).addressUid → self)
    }
  }

}

class RemoteQuarantinePiercingMultiJvmNode1 extends RemoteQuarantinePiercingSpec
class RemoteQuarantinePiercingMultiJvmNode2 extends RemoteQuarantinePiercingSpec

abstract class RemoteQuarantinePiercingSpec extends MultiNodeSpec(RemoteQuarantinePiercingSpec)
  with STMultiNodeSpec
  with ImplicitSender {

  import RemoteQuarantinePiercingSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): (Int, ActorRef) = {
    system.actorSelection(node(role) / "user" / actorName) ! "identify"
    expectMsgType[(Int, ActorRef)]
  }

  "RemoteNodeShutdownAndComesBack" must {

    "allow piercing through the quarantine when remote UID is new" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        enterBarrier("actors-started")

        // Acquire ActorRef from first system
        val (uidFirst, subjectFirst) = identify(second, "subject")
        enterBarrier("actor-identified")

        // Manually Quarantine the other system
        RARP(system).provider.transport.quarantine(node(second).address, Some(uidFirst))

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
            val (uidSecond, subjectSecond) = expectMsgType[(Int, ActorRef)](1.second)
            uidSecond should not be (uidFirst)
            subjectSecond should not be (subjectFirst)
          }
        }

        // If we got here the Quarantine was successfully pierced since it is configured to last 1 day

        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "shutdown"

      }

      runOn(second) {
        val addr = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")

        enterBarrier("actor-identified")

        Await.ready(system.whenTerminated, 30.seconds)

        val freshSystem = ActorSystem(system.name, ConfigFactory.parseString(s"""
                    akka.remote.netty.tcp {
                      hostname = ${addr.host.get}
                      port = ${addr.port.get}
                    }
                    """).withFallback(system.settings.config))
        freshSystem.actorOf(Props[Subject], "subject")

        Await.ready(freshSystem.whenTerminated, 30.seconds)
      }

    }

  }
}

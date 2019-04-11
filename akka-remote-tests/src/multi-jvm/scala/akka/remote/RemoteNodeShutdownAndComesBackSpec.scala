/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.{ Direction, ForceDisassociate }
import akka.remote.testkit.MultiNodeConfig
import akka.testkit._
import akka.actor.ActorIdentity
import akka.remote.testconductor.RoleName
import akka.actor.Identify
import scala.concurrent.Await

object RemoteNodeShutdownAndComesBackSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false).withFallback(
      ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = INFO
      ## Keep it tight, otherwise reestablishing a connection takes too much time
      akka.remote.transport-failure-detector.heartbeat-interval = 1 s
      akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 3 s
      akka.remote.watch-failure-detector.acceptable-heartbeat-pause = 60 s
    """)))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "shutdown" => context.system.terminate()
      case msg        => sender() ! msg
    }
  }

}

class RemoteNodeShutdownAndComesBackMultiJvmNode1 extends RemoteNodeShutdownAndComesBackSpec
class RemoteNodeShutdownAndComesBackMultiJvmNode2 extends RemoteNodeShutdownAndComesBackSpec

abstract class RemoteNodeShutdownAndComesBackSpec extends RemotingMultiNodeSpec(RemoteNodeShutdownAndComesBackSpec) {

  import RemoteNodeShutdownAndComesBackSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  "RemoteNodeShutdownAndComesBack" must {

    "properly reset system message buffer state when new system with same Address comes up" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        system.actorOf(Props[Subject], "subject1")
        enterBarrier("actors-started")

        val subject = identify(second, "subject")
        val sysmsgBarrier = identify(second, "sysmsgBarrier")

        // Prime up the system message buffer
        watch(subject)
        enterBarrier("watch-established")

        // Wait for proper system message propagation
        // (Using a helper actor to ensure that all previous system messages arrived)
        watch(sysmsgBarrier)
        system.stop(sysmsgBarrier)
        expectTerminated(sysmsgBarrier)

        // Drop all messages from this point so no SHUTDOWN is ever received
        testConductor.blackhole(second, first, Direction.Send).await
        // Shut down all existing connections so that the system can enter recovery mode (association attempts)
        Await.result(
          RARP(system).provider.transport.managementCommand(ForceDisassociate(node(second).address)),
          3.seconds)

        // Trigger reconnect attempt and also queue up a system message to be in limbo state (UID of remote system
        // is unknown, and system message is pending)
        system.stop(subject)

        // Get rid of old system -- now SHUTDOWN is lost
        testConductor.shutdown(second).await

        // At this point the second node is restarting, while the first node is trying to reconnect without resetting
        // the system message send state

        // Now wait until second system becomes alive again
        within(30.seconds) {
          // retry because the Subject actor might not be started yet
          awaitAssert {
            val p = TestProbe()
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject").tell(Identify("subject"), p.ref)
            p.expectMsgPF(1 second) {
              case ActorIdentity("subject", Some(ref)) => true
            }
          }
        }

        expectTerminated(subject)

        // Establish watch with the new system. This triggers additional system message traffic. If buffers are out
        // of sync the remote system will be quarantined and the rest of the test will fail (or even in earlier
        // stages depending on circumstances).
        system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! Identify("subject")
        val subjectNew = expectMsgType[ActorIdentity].ref.get
        watch(subjectNew)

        subjectNew ! "shutdown"
        // we are waiting for a Terminated here, but it is ok if it does not arrive
        receiveWhile(5.seconds) {
          case _: ActorIdentity => true
        }
      }

      runOn(second) {
        val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject], "subject")
        system.actorOf(Props[Subject], "sysmsgBarrier")
        val path = node(first)
        enterBarrier("actors-started")

        enterBarrier("watch-established")

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

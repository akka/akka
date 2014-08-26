/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.actor.Terminated
import akka.remote.testconductor.RoleName
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.actor.ExtendedActorSystem
import akka.actor.ActorSystem
import akka.actor.RootActorPath

object RemoteNodeRestartDeathWatchMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = off
      akka.remote.transport-failure-detector.heartbeat-interval = 1 s
      akka.remote.transport-failure-detector.acceptable-heartbeat-pause = 3 s
    """)))

  testTransport(on = true)

  class Subject extends Actor {
    def receive = {
      case "shutdown" ⇒
        sender() ! "shutdown-ack"
        context.system.terminate()
      case msg ⇒ sender() ! msg
    }
  }

}

// Several different variations of the test

class RemoteNodeRestartDeathWatchMultiJvmNode1 extends RemoteNodeRestartDeathWatchSpec
class RemoteNodeRestartDeathWatchMultiJvmNode2 extends RemoteNodeRestartDeathWatchSpec

abstract class RemoteNodeRestartDeathWatchSpec
  extends MultiNodeSpec(RemoteNodeRestartDeathWatchMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender {

  import RemoteNodeRestartDeathWatchMultiJvmSpec._

  override def initialParticipants = roles.size

  def identify(role: RoleName, actorName: String): ActorRef = {
    system.actorSelection(node(role) / "user" / actorName) ! Identify(actorName)
    expectMsgType[ActorIdentity].ref.get
  }

  "RemoteNodeRestartDeathWatch" must {

    "receive Terminated when remote actor system is restarted" taggedAs LongRunningTest in {
      runOn(first) {
        val secondAddress = node(second).address
        enterBarrier("actors-started")

        val subject = identify(second, "subject")
        watch(subject)
        subject ! "hello"
        expectMsg("hello")
        enterBarrier("watch-established")

        // simulate a hard shutdown, nothing sent from the shutdown node
        testConductor.blackhole(second, first, Direction.Send).await
        testConductor.shutdown(second).await

        expectTerminated(subject, 15.seconds)

        within(5.seconds) {
          // retry because the Subject actor might not be started yet
          awaitAssert {
            system.actorSelection(RootActorPath(secondAddress) / "user" / "subject") ! "shutdown"
            expectMsg(1.second, "shutdown-ack")
          }
        }
      }

      runOn(second) {
        val addr = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject], "subject")
        enterBarrier("actors-started")

        enterBarrier("watch-established")

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

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.ExtendedActorSystem
import akka.actor.Identify
import akka.actor.Props
import akka.actor.RootActorPath
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._

class RemoteNodeRestartDeathWatchConfig(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = off
      akka.remote.classic.transport-failure-detector.heartbeat-interval = 1 s
      akka.remote.classic.transport-failure-detector.acceptable-heartbeat-pause = 3 s
      akka.remote.artery.enabled = $artery
      akka.remote.use-unsafe-remote-features-outside-cluster = on
    """)))

  testTransport(on = true)

}

class RemoteNodeRestartDeathWatchMultiJvmNode1
    extends RemoteNodeRestartDeathWatchSpec(new RemoteNodeRestartDeathWatchConfig(artery = false))
class RemoteNodeRestartDeathWatchMultiJvmNode2
    extends RemoteNodeRestartDeathWatchSpec(new RemoteNodeRestartDeathWatchConfig(artery = false))

// FIXME this is failing with Artery
//class ArteryRemoteNodeRestartDeathWatchMultiJvmNode1 extends RemoteNodeRestartDeathWatchSpec(
//  new RemoteNodeRestartDeathWatchConfig(artery = true))
//class ArteryRemoteNodeRestartDeathWatchMultiJvmNode2 extends RemoteNodeRestartDeathWatchSpec(
//  new RemoteNodeRestartDeathWatchConfig(artery = true))

object RemoteNodeRestartDeathWatchSpec {
  class Subject extends Actor {
    def receive = {
      case "shutdown" =>
        sender() ! "shutdown-ack"
        context.system.terminate()
      case msg => sender() ! msg
    }
  }
}

abstract class RemoteNodeRestartDeathWatchSpec(multiNodeConfig: RemoteNodeRestartDeathWatchConfig)
    extends RemotingMultiNodeSpec(multiNodeConfig) {
  import RemoteNodeRestartDeathWatchSpec._
  import multiNodeConfig._

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
        val address = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
        system.actorOf(Props[Subject](), "subject")
        enterBarrier("actors-started")

        enterBarrier("watch-established")

        Await.ready(system.whenTerminated, 30.seconds)

        val freshSystem = ActorSystem(
          system.name,
          ConfigFactory.parseString(s"""
          akka.remote.classic.netty.tcp.port = ${address.port.get}
          akka.remote.artery.canonical.port = ${address.port.get}
          """).withFallback(system.settings.config))
        freshSystem.actorOf(Props[Subject](), "subject")

        Await.ready(freshSystem.whenTerminated, 30.seconds)
      }

    }

  }
}

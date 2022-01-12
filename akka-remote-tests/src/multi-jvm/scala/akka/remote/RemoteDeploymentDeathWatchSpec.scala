/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.util.concurrent.TimeoutException

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import language.postfixOps

import akka.actor.Actor
import akka.actor.ActorSystemImpl
import akka.actor.Props
import akka.remote.testkit.MultiNodeConfig
import akka.testkit._

class RemoteDeploymentDeathWatchMultiJvmSpec(artery: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString(s"""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = off
      akka.remote.artery.enabled = $artery
      akka.remote.use-unsafe-remote-features-outside-cluster = on
      """)).withFallback(RemotingMultiNodeSpec.commonConfig))

  deployOn(second, """/hello.remote = "@third@" """)

}

// Several different variations of the test

class RemoteDeploymentDeathWatchFastMultiJvmNode1 extends RemoteDeploymentNodeDeathWatchFastSpec(artery = false)
class RemoteDeploymentDeathWatchFastMultiJvmNode2 extends RemoteDeploymentNodeDeathWatchFastSpec(artery = false)
class RemoteDeploymentDeathWatchFastMultiJvmNode3 extends RemoteDeploymentNodeDeathWatchFastSpec(artery = false)

class ArteryRemoteDeploymentDeathWatchFastMultiJvmNode1 extends RemoteDeploymentNodeDeathWatchFastSpec(artery = true)
class ArteryRemoteDeploymentDeathWatchFastMultiJvmNode2 extends RemoteDeploymentNodeDeathWatchFastSpec(artery = true)
class ArteryRemoteDeploymentDeathWatchFastMultiJvmNode3 extends RemoteDeploymentNodeDeathWatchFastSpec(artery = true)

abstract class RemoteDeploymentNodeDeathWatchFastSpec(artery: Boolean)
    extends RemoteDeploymentDeathWatchSpec(new RemoteDeploymentDeathWatchMultiJvmSpec(artery)) {
  override def scenario = "fast"
}

class RemoteDeploymentDeathWatchSlowMultiJvmNode1 extends RemoteDeploymentNodeDeathWatchSlowSpec(artery = false)
class RemoteDeploymentDeathWatchSlowMultiJvmNode2 extends RemoteDeploymentNodeDeathWatchSlowSpec(artery = false)
class RemoteDeploymentDeathWatchSlowMultiJvmNode3 extends RemoteDeploymentNodeDeathWatchSlowSpec(artery = false)

class ArteryRemoteDeploymentDeathWatchSlowMultiJvmNode1 extends RemoteDeploymentNodeDeathWatchSlowSpec(artery = true)
class ArteryRemoteDeploymentDeathWatchSlowMultiJvmNode2 extends RemoteDeploymentNodeDeathWatchSlowSpec(artery = true)
class ArteryRemoteDeploymentDeathWatchSlowMultiJvmNode3 extends RemoteDeploymentNodeDeathWatchSlowSpec(artery = true)

abstract class RemoteDeploymentNodeDeathWatchSlowSpec(artery: Boolean)
    extends RemoteDeploymentDeathWatchSpec(new RemoteDeploymentDeathWatchMultiJvmSpec(artery)) {
  override def scenario = "slow"
  override def sleep(): Unit = Thread.sleep(3000)
}

object RemoteDeploymentDeathWatchSpec {
  class Hello extends Actor {
    def receive = Actor.emptyBehavior
  }
}

abstract class RemoteDeploymentDeathWatchSpec(multiNodeConfig: RemoteDeploymentDeathWatchMultiJvmSpec)
    extends RemotingMultiNodeSpec(multiNodeConfig) {
  import RemoteDeploymentDeathWatchSpec._
  import multiNodeConfig._

  def scenario: String
  // Possible to override to let them heartbeat for a while.
  def sleep(): Unit = ()

  override def initialParticipants = roles.size

  "An actor system that deploys actors on another node" must {

    "be able to shutdown when remote node crash" taggedAs LongRunningTest in within(20 seconds) {
      runOn(second) {
        // remote deployment to third
        val hello = system.actorOf(Props[Hello](), "hello")
        hello.path.address should ===(node(third).address)
        enterBarrier("hello-deployed")

        enterBarrier("third-crashed")

        sleep()
        // if the remote deployed actor is not removed the system will not shutdown

        val timeout = remainingOrDefault
        try Await.ready(system.whenTerminated, timeout)
        catch {
          case _: TimeoutException =>
            fail(
              "Failed to stop [%s] within [%s] \n%s"
                .format(system.name, timeout, system.asInstanceOf[ActorSystemImpl].printTree))
        }
      }

      runOn(third) {
        enterBarrier("hello-deployed")
        enterBarrier("third-crashed")
      }

      runOn(first) {
        enterBarrier("hello-deployed")
        sleep()
        testConductor.exit(third, 0).await
        enterBarrier("third-crashed")

        // second system will be shutdown
        testConductor.shutdown(second).await

        enterBarrier("after-3")
      }

    }

  }
}

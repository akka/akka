/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import language.postfixOps
import java.util.concurrent.TimeoutException
import scala.concurrent.Await
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.ActorSystemImpl
import akka.actor.Props
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit._
import akka.testkit.TestEvent._

object RemoteDeploymentDeathWatchMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(
    ConfigFactory.parseString("""
      akka.loglevel = INFO
      akka.remote.log-remote-lifecycle-events = off
      """)))

  deployOn(second, """/hello.remote = "@third@" """)

  class Hello extends Actor {
    def receive = Actor.emptyBehavior
  }
}

// Several different variations of the test

class RemoteDeploymentDeathWatchFastMultiJvmNode1 extends RemoteDeploymentNodeDeathWatchFastSpec
class RemoteDeploymentDeathWatchFastMultiJvmNode2 extends RemoteDeploymentNodeDeathWatchFastSpec
class RemoteDeploymentDeathWatchFastMultiJvmNode3 extends RemoteDeploymentNodeDeathWatchFastSpec
abstract class RemoteDeploymentNodeDeathWatchFastSpec extends RemoteDeploymentDeathWatchSpec {
  override def scenario = "fast"
}

class RemoteDeploymentDeathWatchSlowMultiJvmNode1 extends RemoteDeploymentNodeDeathWatchSlowSpec
class RemoteDeploymentDeathWatchSlowMultiJvmNode2 extends RemoteDeploymentNodeDeathWatchSlowSpec
class RemoteDeploymentDeathWatchSlowMultiJvmNode3 extends RemoteDeploymentNodeDeathWatchSlowSpec
abstract class RemoteDeploymentNodeDeathWatchSlowSpec extends RemoteDeploymentDeathWatchSpec {
  override def scenario = "slow"
  override def sleep(): Unit = Thread.sleep(3000)
}

abstract class RemoteDeploymentDeathWatchSpec
  extends MultiNodeSpec(RemoteDeploymentDeathWatchMultiJvmSpec)
  with STMultiNodeSpec with ImplicitSender {

  import RemoteDeploymentDeathWatchMultiJvmSpec._

  def scenario: String
  // Possible to override to let them heartbeat for a while.
  def sleep(): Unit = ()

  override def initialParticipants = roles.size

  "An actor system that deploys actors on another node" must {

    "be able to shutdown when remote node crash" taggedAs LongRunningTest in within(20 seconds) {
      runOn(second) {
        // remote deployment to third
        val hello = system.actorOf(Props[Hello], "hello")
        hello.path.address should ===(node(third).address)
        enterBarrier("hello-deployed")

        enterBarrier("third-crashed")

        sleep()
        // if the remote deployed actor is not removed the system will not shutdown

        val timeout = remainingOrDefault
        try Await.ready(system.whenTerminated, timeout) catch {
          case _: TimeoutException ⇒
            fail("Failed to stop [%s] within [%s] \n%s".format(system.name, timeout,
              system.asInstanceOf[ActorSystemImpl].printTree))
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

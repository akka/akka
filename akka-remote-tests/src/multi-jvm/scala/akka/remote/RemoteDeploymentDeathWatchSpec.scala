/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote

import language.postfixOps
import java.util.concurrent.TimeoutException
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
        hello.path.address must be(node(third).address)
        enterBarrier("hello-deployed")

        enterBarrier("third-crashed")

        sleep()
        // if the remote deployed actor is not removed the system will not shutdown
        system.shutdown()
        val timeout = remaining
        try system.awaitTermination(timeout) catch {
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
        testConductor.shutdown(third, 0).await
        enterBarrier("third-crashed")

        runOn(first) {
          // second system will be shutdown, remove to not participate in barriers any more
          testConductor.removeNode(second)
        }

        enterBarrier("after-3")
      }

    }

  }
}

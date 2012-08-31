/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Props
import akka.actor.Actor
import akka.actor.RootActorPath
import akka.actor.Terminated

object ClusterDeathWatchMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class ClusterDeathWatchMultiJvmNode1 extends ClusterDeathWatchSpec with FailureDetectorPuppetStrategy
class ClusterDeathWatchMultiJvmNode2 extends ClusterDeathWatchSpec with FailureDetectorPuppetStrategy
class ClusterDeathWatchMultiJvmNode3 extends ClusterDeathWatchSpec with FailureDetectorPuppetStrategy

abstract class ClusterDeathWatchSpec
  extends MultiNodeSpec(ClusterDeathWatchMultiJvmSpec)
  with MultiNodeClusterSpec {

  import ClusterDeathWatchMultiJvmSpec._

  "An actor watching a remote actor in the cluster" must {
    "receive Terminated when watched node becomes unreachable" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      enterBarrier("cluster-up")

      runOn(first) {
        enterBarrier("subjected-started")

        val path2 = RootActorPath(second) / "user" / "subject"
        val path3 = RootActorPath(third) / "user" / "subject"
        val watchEstablished = TestLatch(1)
        system.actorOf(Props(new Actor {

          context.watch(context.actorFor(path2))
          context.watch(context.actorFor(path3))
          watchEstablished.countDown

          def receive = {
            case t: Terminated ⇒ testActor ! t.actor.path
          }
        }), name = "observer")

        watchEstablished.await
        enterBarrier("watch-established")
        expectMsg(path2)
        enterBarrier("second-terminated")

        markNodeAsUnavailable(third)
        expectMsg(path3)
        enterBarrier("third-terminated")

      }

      runOn(second, third) {
        system.actorOf(Props(new Actor { def receive = Actor.emptyBehavior }), name = "subject")
        enterBarrier("subjected-started")
        enterBarrier("watch-established")
        runOn(third) {
          markNodeAsUnavailable(second)
        }
        enterBarrier("second-terminated")
        enterBarrier("third-terminated")
      }

      enterBarrier("after")

    }

  }
}

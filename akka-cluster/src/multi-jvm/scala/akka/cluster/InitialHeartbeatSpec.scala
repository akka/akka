/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import scala.concurrent.duration._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.Props
import akka.cluster.ClusterEvent.CurrentClusterState
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.testkit._

object InitialHeartbeatMultiJvmSpec extends MultiNodeConfig {
  val controller = role("controller")
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster.failure-detector.heartbeat-request.grace-period = 3 s
      akka.cluster.failure-detector.threshold = 4""")).
    withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

class InitialHeartbeatMultiJvmNode1 extends InitialHeartbeatSpec
class InitialHeartbeatMultiJvmNode2 extends InitialHeartbeatSpec
class InitialHeartbeatMultiJvmNode3 extends InitialHeartbeatSpec

abstract class InitialHeartbeatSpec
  extends MultiNodeSpec(InitialHeartbeatMultiJvmSpec)
  with MultiNodeClusterSpec {

  import InitialHeartbeatMultiJvmSpec._

  muteMarkingAsUnreachable()

  "A member" must {

    "detect failure even though no heartbeats have been received" taggedAs LongRunningTest in {
      val secondAddress = address(second)
      awaitClusterUp(first)

      runOn(first) {
        within(10 seconds) {
          awaitCond {
            cluster.sendCurrentClusterState(testActor)
            expectMsgType[CurrentClusterState].members.exists(_.address == secondAddress)
          }
        }
      }
      runOn(second) {
        cluster.join(first)
      }
      enterBarrier("second-joined")

      runOn(controller) {
        // it is likely that first has not started sending heartbeats to second yet
        // Direction must be Receive because the gossip from first to second must pass through
        testConductor.blackhole(first, second, Direction.Receive).await
      }

      runOn(second) {
        within(15 seconds) {
          awaitCond(!cluster.failureDetector.isAvailable(first))
        }
      }

      enterBarrier("after-1")
    }
  }
}

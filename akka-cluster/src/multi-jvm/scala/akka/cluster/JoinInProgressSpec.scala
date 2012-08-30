/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._
import scala.concurrent.util.Deadline

object JoinInProgressMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
          akka.cluster {
            # simulate delay in gossip by turning it off
            gossip-interval = 300 s
            failure-detector {
              threshold = 4
              acceptable-heartbeat-pause = 1 second
            }
          }""")
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class JoinInProgressMultiJvmNode1 extends JoinInProgressSpec with AccrualFailureDetectorStrategy
class JoinInProgressMultiJvmNode2 extends JoinInProgressSpec with AccrualFailureDetectorStrategy

abstract class JoinInProgressSpec
  extends MultiNodeSpec(JoinInProgressMultiJvmSpec)
  with MultiNodeClusterSpec {

  import JoinInProgressMultiJvmSpec._

  "A cluster node" must {
    "send heartbeats immediately when joining to avoid false failure detection due to delayed gossip" taggedAs LongRunningTest in {

      runOn(first) {
        startClusterNode()
      }

      enterBarrier("first-started")

      runOn(second) {
        cluster.join(first)
      }

      runOn(first) {
        val until = Deadline.now + 5.seconds
        while (!until.isOverdue) {
          Thread.sleep(200)
          cluster.failureDetector.isAvailable(second) must be(true)
        }
      }

      enterBarrier("after")
    }
  }
}

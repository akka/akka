/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import scala.collection.immutable.SortedSet
import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.concurrent.util.duration._

object NodeLeavingAndExitingMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("""
          # turn off unreachable reaper
          akka.cluster.unreachable-nodes-reaper-interval = 300 s""")
        .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class NodeLeavingAndExitingMultiJvmNode1 extends NodeLeavingAndExitingSpec with FailureDetectorPuppetStrategy
class NodeLeavingAndExitingMultiJvmNode2 extends NodeLeavingAndExitingSpec with FailureDetectorPuppetStrategy
class NodeLeavingAndExitingMultiJvmNode3 extends NodeLeavingAndExitingSpec with FailureDetectorPuppetStrategy

abstract class NodeLeavingAndExitingSpec
  extends MultiNodeSpec(NodeLeavingAndExitingMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeLeavingAndExitingMultiJvmSpec._

  "A node that is LEAVING a non-singleton cluster" must {

    "be moved to EXITING by the leader" taggedAs LongRunningTest in {

      awaitClusterUp(first, second, third)

      runOn(first, third) {
        val secondAddess = address(second)
        val leavingLatch = TestLatch()
        val exitingLatch = TestLatch()
        val expectedAddresses = roles.toSet map address
        cluster.registerListener(new MembershipChangeListener {
          def notify(members: SortedSet[Member]) {
            def check(status: MemberStatus): Boolean =
              (members.map(_.address) == expectedAddresses &&
                members.exists(m ⇒ m.address == secondAddess && m.status == status))
            if (check(MemberStatus.Leaving)) leavingLatch.countDown()
            if (check(MemberStatus.Exiting)) exitingLatch.countDown()
          }
        })
        enterBarrier("registered-listener")

        runOn(third) {
          cluster.leave(second)
        }
        enterBarrier("second-left")

        // Verify that 'second' node is set to LEAVING
        leavingLatch.await

        // Verify that 'second' node is set to EXITING
        exitingLatch.await

      }

      // node that is leaving
      runOn(second) {
        enterBarrier("registered-listener")
        enterBarrier("second-left")
      }

      enterBarrier("finished")
    }
  }
}

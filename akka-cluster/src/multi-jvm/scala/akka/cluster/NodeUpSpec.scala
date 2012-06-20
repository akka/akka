/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.util.duration._
import scala.collection.immutable.SortedSet
import java.util.concurrent.atomic.AtomicReference

object NodeUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class NodeUpMultiJvmNode1 extends NodeUpSpec with FailureDetectorPuppetStrategy
class NodeUpMultiJvmNode2 extends NodeUpSpec with FailureDetectorPuppetStrategy

abstract class NodeUpSpec
  extends MultiNodeSpec(NodeUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeUpMultiJvmSpec._

  "A cluster node that is joining another cluster" must {
    "be moved to UP by the leader after a convergence" taggedAs LongRunningTest in {

      awaitClusterUp(first, second)

      enterBarrier("after-1")
    }

    "be unaffected when joining again" taggedAs LongRunningTest in {

      val unexpected = new AtomicReference[SortedSet[Member]]
      cluster.registerListener(new MembershipChangeListener {
        def notify(members: SortedSet[Member]) {
          if (members.size != 2 || members.exists(_.status != MemberStatus.Up))
            unexpected.set(members)
        }
      })
      enterBarrier("listener-registered")

      runOn(second) {
        cluster.join(first)
      }
      enterBarrier("joined-again")

      // let it run for a while to make sure that nothing bad happens
      for (n ← 1 to 20) {
        100.millis.dilated.sleep()
        unexpected.get must be(null)
        cluster.latestGossip.members.forall(_.status == MemberStatus.Up) must be(true)
      }

      enterBarrier("after-2")
    }
  }
}

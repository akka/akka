/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import java.util.concurrent.atomic.AtomicReference
import akka.actor.Props
import akka.actor.Actor
import akka.cluster.MemberStatus._

object MinMembersBeforeUpMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
          # turn off unreachable reaper
          akka.cluster.min-nr-of-members = 3""")).
    withFallback(MultiNodeClusterSpec.clusterConfigWithFailureDetectorPuppet))
}

class MinMembersBeforeUpMultiJvmNode1 extends MinMembersBeforeUpSpec
class MinMembersBeforeUpMultiJvmNode2 extends MinMembersBeforeUpSpec
class MinMembersBeforeUpMultiJvmNode3 extends MinMembersBeforeUpSpec

abstract class MinMembersBeforeUpSpec
  extends MultiNodeSpec(MinMembersBeforeUpMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MinMembersBeforeUpMultiJvmSpec._
  import ClusterEvent._

  "Cluster leader" must {
    "wait with moving members to UP until minimum number of members have joined" taggedAs LongRunningTest in {

      val onUpLatch = TestLatch(1)
      cluster.registerOnMemberUp(onUpLatch.countDown())

      runOn(first) {
        startClusterNode()
        awaitCond(clusterView.status == Joining)
      }
      enterBarrier("first-started")

      onUpLatch.isOpen must be(false)

      runOn(second) {
        cluster.join(first)
      }
      runOn(first, second) {
        val expectedAddresses = Set(first, second) map address
        awaitCond(clusterView.members.map(_.address) == expectedAddresses)
        clusterView.members.map(_.status) must be(Set(Joining))
        // and it should not change
        1 to 5 foreach { _ ⇒
          Thread.sleep(1000)
          clusterView.members.map(_.address) must be(expectedAddresses)
          clusterView.members.map(_.status) must be(Set(Joining))
        }
      }
      enterBarrier("second-joined")

      runOn(third) {
        cluster.join(first)
      }
      awaitClusterUp(first, second, third)

      onUpLatch.await

      enterBarrier("after-1")
    }

  }
}

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

object NodeJoinMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")

  commonConfig(
    debugConfig(on = false)
      .withFallback(ConfigFactory.parseString("akka.cluster.leader-actions-interval = 5 s") // increase the leader action task interval
      .withFallback(MultiNodeClusterSpec.clusterConfig)))
}

class NodeJoinMultiJvmNode1 extends NodeJoinSpec with FailureDetectorPuppetStrategy
class NodeJoinMultiJvmNode2 extends NodeJoinSpec with FailureDetectorPuppetStrategy

abstract class NodeJoinSpec
  extends MultiNodeSpec(NodeJoinMultiJvmSpec)
  with MultiNodeClusterSpec {

  import NodeJoinMultiJvmSpec._

  lazy val firstAddress = node(first).address
  lazy val secondAddress = node(second).address

  "A cluster node" must {
    "join another cluster and get status JOINING - when sending a 'Join' command" taggedAs LongRunningTest in {

      runOn(first) {
        startClusterNode()
      }

      runOn(second) {
        cluster.join(firstAddress)
      }

      awaitCond(cluster.latestGossip.members.exists { member ⇒ member.address == secondAddress && member.status == MemberStatus.Joining })

      enter("after")
    }
  }
}

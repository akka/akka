/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import akka.actor.Address
import akka.util.duration._

object LeaderDowningNodeThatIsUnreachableMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = true).
    withFallback(ConfigFactory.parseString("""
      akka.cluster {
        auto-down = on
        failure-detector.threshold = 4
      }
    """)).
    withFallback(MultiNodeClusterSpec.clusterConfig))
}

class LeaderDowningNodeThatIsUnreachableMultiJvmNode1 extends LeaderDowningNodeThatIsUnreachableSpec
class LeaderDowningNodeThatIsUnreachableMultiJvmNode2 extends LeaderDowningNodeThatIsUnreachableSpec
class LeaderDowningNodeThatIsUnreachableMultiJvmNode3 extends LeaderDowningNodeThatIsUnreachableSpec
class LeaderDowningNodeThatIsUnreachableMultiJvmNode4 extends LeaderDowningNodeThatIsUnreachableSpec

class LeaderDowningNodeThatIsUnreachableSpec
  extends MultiNodeSpec(LeaderDowningNodeThatIsUnreachableMultiJvmSpec)
  with MultiNodeClusterSpec {

  import LeaderDowningNodeThatIsUnreachableMultiJvmSpec._

  "The Leader in a 4 node cluster" must {

    "be able to DOWN a 'last' node that is UNREACHABLE" taggedAs LongRunningTest in {
      val fourthAddress = node(fourth).address
      awaitClusterUp(first, second, third, fourth)

      runOn(first) {
        // kill 'fourth' node
        testConductor.shutdown(fourth, 0)
        testConductor.enter("down-fourth-node")

        // --- HERE THE LEADER SHOULD DETECT FAILURE AND AUTO-DOWN THE UNREACHABLE NODE ---

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(fourthAddress), 30.seconds)
      }

      runOn(fourth) {
        testConductor.enter("down-fourth-node")
      }

      runOn(second, third) {
        testConductor.enter("down-fourth-node")

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(fourthAddress), 30.seconds)
      }

      testConductor.enter("await-completion-1")
    }

    "be able to DOWN a 'middle' node that is UNREACHABLE" taggedAs LongRunningTest in {
      val secondAddress = node(second).address
      testConductor.enter("before-down-second-node")

      runOn(first) {
        // kill 'second' node
        testConductor.shutdown(second, 0)
        testConductor.enter("down-second-node")

        // --- HERE THE LEADER SHOULD DETECT FAILURE AND AUTO-DOWN THE UNREACHABLE NODE ---

        awaitUpConvergence(numberOfMembers = 2, canNotBePartOfMemberRing = Seq(secondAddress), 30.seconds)
      }

      runOn(second) {
        testConductor.enter("down-second-node")
      }

      runOn(third) {
        testConductor.enter("down-second-node")

        awaitUpConvergence(numberOfMembers = 2, canNotBePartOfMemberRing = Seq(secondAddress), 30 seconds)
      }

      testConductor.enter("await-completion-2")
    }
  }
}

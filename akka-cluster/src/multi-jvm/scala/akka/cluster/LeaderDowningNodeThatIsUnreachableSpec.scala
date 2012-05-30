/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfter
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

  commonConfig(debugConfig(on = false).
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
  with MultiNodeClusterSpec
  with ImplicitSender with BeforeAndAfter {
  import LeaderDowningNodeThatIsUnreachableMultiJvmSpec._

  override def initialParticipants = 4

  "The Leader in a 4 node cluster" must {

    "be able to DOWN a 'last' node that is UNREACHABLE" taggedAs LongRunningTest in {
      runOn(first) {
        cluster.self
        awaitUpConvergence(numberOfMembers = 4)

        val fourthAddress = node(fourth).address
        testConductor.enter("all-up")

        // kill 'fourth' node
        testConductor.shutdown(fourth, 0)
        testConductor.removeNode(fourth)
        testConductor.enter("down-fourth-node")

        // --- HERE THE LEADER SHOULD DETECT FAILURE AND AUTO-DOWN THE UNREACHABLE NODE ---

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(fourthAddress), 30.seconds)
        testConductor.enter("await-completion")
      }

      runOn(fourth) {
        cluster.join(node(first).address)

        awaitUpConvergence(numberOfMembers = 4)
        testConductor.enter("all-up")
      }

      runOn(second, third) {
        cluster.join(node(first).address)
        awaitUpConvergence(numberOfMembers = 4)

        val fourthAddress = node(fourth).address
        testConductor.enter("all-up")

        testConductor.enter("down-fourth-node")

        awaitUpConvergence(numberOfMembers = 3, canNotBePartOfMemberRing = Seq(fourthAddress), 30.seconds)
        testConductor.enter("await-completion")
      }
    }

    "be able to DOWN a 'middle' node that is UNREACHABLE" taggedAs LongRunningTest in {
      runOn(first) {
        cluster.self
        awaitUpConvergence(numberOfMembers = 3)

        val secondAddress = node(second).address
        testConductor.enter("all-up")

        // kill 'second' node
        testConductor.shutdown(second, 0)
        testConductor.removeNode(second)
        testConductor.enter("down-second-node")

        // --- HERE THE LEADER SHOULD DETECT FAILURE AND AUTO-DOWN THE UNREACHABLE NODE ---

        awaitUpConvergence(numberOfMembers = 2, canNotBePartOfMemberRing = Seq(secondAddress), 30.seconds)
        testConductor.enter("await-completion")
      }

      runOn(second) {
        cluster.join(node(first).address)

        awaitUpConvergence(numberOfMembers = 3)
        testConductor.enter("all-up")
      }

      runOn(third) {
        cluster.join(node(first).address)
        awaitUpConvergence(numberOfMembers = 3)

        val secondAddress = node(second).address
        testConductor.enter("all-up")

        testConductor.enter("down-second-node")

        awaitUpConvergence(numberOfMembers = 2, canNotBePartOfMemberRing = Seq(secondAddress), 30 seconds)
        testConductor.enter("await-completion")
      }
    }
  }
}

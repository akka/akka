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
import akka.actor.Address

object ClientDowningNodeThatIsUpMultiJvmSpec extends MultiNodeConfig {
  val first  = role("first")
  val second = role("second")
  val third  = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster {
      gossip-frequency             = 100 ms
      leader-actions-frequency     = 100 ms
      periodic-tasks-initial-delay = 300 ms
      auto-down                    = off
    }
    """)))
}

class ClientDowningNodeThatIsUpMultiJvmNode1 extends ClientDowningNodeThatIsUpSpec
class ClientDowningNodeThatIsUpMultiJvmNode2 extends ClientDowningNodeThatIsUpSpec
class ClientDowningNodeThatIsUpMultiJvmNode3 extends ClientDowningNodeThatIsUpSpec
class ClientDowningNodeThatIsUpMultiJvmNode4 extends ClientDowningNodeThatIsUpSpec

class ClientDowningNodeThatIsUpSpec extends MultiNodeSpec(ClientDowningNodeThatIsUpMultiJvmSpec) with ImplicitSender with BeforeAndAfter {
  import ClientDowningNodeThatIsUpMultiJvmSpec._

  override def initialParticipants = 4

  def node = Cluster(system)

  def assertMemberRing(nrOfMembers: Int, canNotBePartOfRing: Seq[Address] = Seq.empty[Address]): Unit = {
    awaitCond(node.latestGossip.members.size == nrOfMembers)
    awaitCond(node.latestGossip.members.forall(_.status == MemberStatus.Up))
    awaitCond(canNotBePartOfRing forall (address => !(node.latestGossip.members exists (_.address.port == address.port))))
  }

  "Client of a 4 node cluster" must {

    "be able to DOWN a node that is UP (healthy and available)" taggedAs LongRunningTest in {
      runOn(first) {
        node.self
        assertMemberRing(nrOfMembers = 4)
        testConductor.enter("all-up")

        val thirdAddress = node(third).address

        // mark 'third' node as DOWN
        testConductor.removeNode(third)
        node.down(thirdAddress)
        testConductor.enter("down-third-node")

        assertMemberRing(nrOfMembers = 3, canNotBePartOfRing = Seq(thirdAddress))
        node.latestGossip.members.exists(_.address == thirdAddress) must be(false)
        testConductor.enter("await-completion")
      }

      runOn(second) {
        node.join(node(first).address)

        assertMemberRing(nrOfMembers = 4)
        testConductor.enter("all-up")

        val thirdAddress = node(third).address
        testConductor.enter("down-third-node")

        assertMemberRing(nrOfMembers = 3, canNotBePartOfRing = Seq(thirdAddress))
        testConductor.enter("await-completion")
      }

      runOn(third) {
        node.join(node(first).address)

        assertMemberRing(nrOfMembers = 4)
        testConductor.enter("all-up")
      }

      runOn(fourth) {
        node.join(node(first).address)

        assertMemberRing(nrOfMembers = 4)
        testConductor.enter("all-up")

        val thirdAddress = node(third).address
        testConductor.enter("down-third-node")

        assertMemberRing(nrOfMembers = 3, canNotBePartOfRing = Seq(thirdAddress))
        testConductor.enter("await-completion")
      }
    }
  }
}

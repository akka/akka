/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.actor.Address
import akka.remote.testconductor.{RoleName, Direction}
import akka.util.duration._

object UnreachableNodeRejoinsClusterMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  val allRoles = Seq(first, second, third, fourth)

  def allBut(role: RoleName, roles: Seq[RoleName] = allRoles): Seq[RoleName] = {
    roles.filterNot(_ == role)
  }

  commonConfig(debugConfig(on = false).
    withFallback(ConfigFactory.parseString("""
      akka.cluster {
        failure-detector.threshold = 5
      }                                    """)
  ).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class UnreachableNodeRejoinsClusterMultiJvmNode1 extends UnreachableNodeRejoinsClusterSpec
class UnreachableNodeRejoinsClusterMultiJvmNode2 extends UnreachableNodeRejoinsClusterSpec
class UnreachableNodeRejoinsClusterMultiJvmNode3 extends UnreachableNodeRejoinsClusterSpec
class UnreachableNodeRejoinsClusterMultiJvmNode4 extends UnreachableNodeRejoinsClusterSpec

class UnreachableNodeRejoinsClusterSpec
  extends MultiNodeSpec(UnreachableNodeRejoinsClusterMultiJvmSpec)
  with MultiNodeClusterSpec
  with ImplicitSender with BeforeAndAfter {
  import UnreachableNodeRejoinsClusterMultiJvmSpec._

  override def initialParticipants = allRoles.size

  lazy val sortedRoles = clusterSortedRoles(allRoles)
  lazy val master = sortedRoles(0)
  lazy val victim = sortedRoles(1)

  var endBarrierNumber = 0
  def endBarrier: Unit = {
    endBarrierNumber += 1
    testConductor.enter("after_" + endBarrierNumber)
  }

  "A cluster of " + allRoles.size + " members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(allRoles:_*)
      endBarrier
    }

    "mark a node as UNREACHABLE when we pull the network" taggedAs LongRunningTest in {
      runOn(first) {
        // pull network for victim node from all nodes
        allBut(victim).foreach { roleName =>
          testConductor.blackhole(victim, roleName, Direction.Both).await
        }
      }

      testConductor.enter("unplug_victim")

      runOn(victim) {
        val otherAddresses = sortedRoles.collect { case x if x != victim => node(x).address }
        within(30 seconds) {
          // victim becomes all alone
          awaitCond({ val gossip = cluster.latestGossip
            gossip.overview.unreachable.size == (allRoles.size - 1) &&
              gossip.members.size == 1 &&
              gossip.members.forall(_.status == MemberStatus.Up) })
          cluster.latestGossip.overview.unreachable.map(_.address) must be(otherAddresses.toSet)
          cluster.convergence.isDefined must be(false)
        }
      }

      val allButVictim = allBut(victim)
      runOn(allButVictim:_*) {
        val victimAddress = node(victim).address
        val otherAddresses = allButVictim.map(node(_).address)
        within(30 seconds) {
          // victim becomes unreachable
          awaitCond({ val gossip = cluster.latestGossip
            gossip.overview.unreachable.size == 1 &&
              gossip.members.size == (allRoles.size - 1) &&
              gossip.members.forall(_.status == MemberStatus.Up) })
          awaitSeenSameState(otherAddresses)
          // still one unreachable
          cluster.latestGossip.overview.unreachable.size must be(1)
          cluster.latestGossip.overview.unreachable.head.address must be(victimAddress)
          // and therefore no convergence
          cluster.convergence.isDefined must be(false)
        }
      }

      endBarrier
    }

    "mark the node as DOWN" taggedAs LongRunningTest in {
      val victimAddress = node(victim).address
      runOn(master) {
        cluster.down(victimAddress)
      }

      runOn(allBut(victim):_*) {
        awaitUpConvergence(allRoles.size - 1, Seq(victimAddress))
      }

      endBarrier
    }

    "allow node to REJOIN when the network is plugged back in" taggedAs LongRunningTest in {
      runOn(first) {
        // put the network back in
        allBut(victim).foreach { roleName =>
          testConductor.passThrough(victim, roleName, Direction.Both).await
        }
      }

      testConductor.enter("plug_in_victim")

      runOn(victim) {
        cluster.join(node(master).address)
      }

      awaitUpConvergence(allRoles.size)

      endBarrier
    }
  }
}

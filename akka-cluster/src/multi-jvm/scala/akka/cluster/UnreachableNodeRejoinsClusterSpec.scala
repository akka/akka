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

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))
}

class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode1 extends UnreachableNodeRejoinsClusterSpec with FailureDetectorPuppetStrategy
class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode2 extends UnreachableNodeRejoinsClusterSpec with FailureDetectorPuppetStrategy
class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode3 extends UnreachableNodeRejoinsClusterSpec with FailureDetectorPuppetStrategy
class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode4 extends UnreachableNodeRejoinsClusterSpec with FailureDetectorPuppetStrategy


class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode1 extends UnreachableNodeRejoinsClusterSpec with AccrualFailureDetectorStrategy
class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode2 extends UnreachableNodeRejoinsClusterSpec with AccrualFailureDetectorStrategy
class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode3 extends UnreachableNodeRejoinsClusterSpec with AccrualFailureDetectorStrategy
class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode4 extends UnreachableNodeRejoinsClusterSpec with AccrualFailureDetectorStrategy

abstract class UnreachableNodeRejoinsClusterSpec
  extends MultiNodeSpec(UnreachableNodeRejoinsClusterMultiJvmSpec)
  with MultiNodeClusterSpec {
  import UnreachableNodeRejoinsClusterMultiJvmSpec._

  def allBut(role: RoleName, roles: Seq[RoleName] = roles): Seq[RoleName] = {
    roles.filterNot(_ == role)
  }


  lazy val sortedRoles = roles.sorted
  lazy val master = sortedRoles(0)
  lazy val victim = sortedRoles(1)

  var endBarrierNumber = 0
  def endBarrier: Unit = {
    endBarrierNumber += 1
    enterBarrier("after_" + endBarrierNumber)
  }

  "A cluster of " + roles.size + " members" must {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(roles:_*)
      endBarrier
    }

    "mark a node as UNREACHABLE when we pull the network" taggedAs LongRunningTest in {
      runOn(first) {
        // pull network for victim node from all nodes
        allBut(victim).foreach { roleName =>
          testConductor.blackhole(victim, roleName, Direction.Both).await
        }
      }

      enterBarrier("unplug_victim")

      val allButVictim = allBut(victim, sortedRoles)
      runOn(victim) {
        allButVictim.foreach(markNodeAsUnavailable(_))
        within(30 seconds) {
          // victim becomes all alone
          awaitCond({ val gossip = cluster.latestGossip
            gossip.overview.unreachable.size == (roles.size - 1) &&
              gossip.members.size == 1 &&
              gossip.members.forall(_.status == MemberStatus.Up) })
          cluster.latestGossip.overview.unreachable.map(_.address) must be((allButVictim map address).toSet)
          cluster.convergence.isDefined must be(false)
        }
      }

      runOn(allButVictim:_*) {
        markNodeAsUnavailable(victim)
        within(30 seconds) {
          // victim becomes unreachable
          awaitCond({ val gossip = cluster.latestGossip
            gossip.overview.unreachable.size == 1 &&
              gossip.members.size == (roles.size - 1) &&
              gossip.members.forall(_.status == MemberStatus.Up) })
          awaitSeenSameState(allButVictim map address:_*)
          // still one unreachable
          cluster.latestGossip.overview.unreachable.size must be(1)
          cluster.latestGossip.overview.unreachable.head.address must be(node(victim).address)
          // and therefore no convergence
          cluster.convergence.isDefined must be(false)
        }
      }

      endBarrier
    }

    "mark the node as DOWN" taggedAs LongRunningTest in {
      runOn(master) {
        cluster down victim
      }

      runOn(allBut(victim):_*) {
        awaitUpConvergence(roles.size - 1, Seq(victim))
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

      enterBarrier("plug_in_victim")

      runOn(victim) {
        cluster join master
      }

      awaitUpConvergence(roles.size)

      endBarrier
    }
  }
}

/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps

import org.scalatest.BeforeAndAfter
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import com.typesafe.config.ConfigFactory
import akka.actor.Address
import akka.remote.testconductor.{ RoleName, Direction }
import scala.concurrent.duration._

case class UnreachableNodeRejoinsClusterMultiNodeConfig(failureDetectorPuppet: Boolean) extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)
}

class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode1 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = true)
class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode2 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = true)
class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode3 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = true)
class UnreachableNodeRejoinsClusterWithFailureDetectorPuppetMultiJvmNode4 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = true)

class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode1 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = false)
class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode2 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = false)
class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode3 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = false)
class UnreachableNodeRejoinsClusterWithAccrualFailureDetectorMultiJvmNode4 extends UnreachableNodeRejoinsClusterSpec(failureDetectorPuppet = false)

abstract class UnreachableNodeRejoinsClusterSpec(multiNodeConfig: UnreachableNodeRejoinsClusterMultiNodeConfig)
  extends MultiNodeSpec(multiNodeConfig)
  with MultiNodeClusterSpec {

  def this(failureDetectorPuppet: Boolean) = this(UnreachableNodeRejoinsClusterMultiNodeConfig(failureDetectorPuppet))

  import multiNodeConfig._

  muteMarkingAsUnreachable()

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

  "A cluster of " + roles.size + " members" ignore {

    "reach initial convergence" taggedAs LongRunningTest in {
      awaitClusterUp(roles: _*)
      endBarrier
    }

    "mark a node as UNREACHABLE when we pull the network" taggedAs LongRunningTest in {
      // let them send at least one heartbeat to each other after the gossip convergence
      // because for new joining nodes we remove them from the failure detector when
      // receive gossip
      Thread.sleep(2.seconds.dilated.toMillis)

      runOn(first) {
        // pull network for victim node from all nodes
        allBut(victim).foreach { roleName ⇒
          testConductor.blackhole(victim, roleName, Direction.Both).await
        }
      }

      enterBarrier("unplug_victim")

      val allButVictim = allBut(victim, sortedRoles)
      runOn(victim) {
        allButVictim.foreach(markNodeAsUnavailable(_))
        within(30 seconds) {
          // victim becomes all alone
          awaitCond({
            val members = clusterView.members
            clusterView.unreachableMembers.size == (roles.size - 1) &&
              members.size == 1 &&
              members.forall(_.status == MemberStatus.Up)
          })
          clusterView.unreachableMembers.map(_.address) must be((allButVictim map address).toSet)
        }
      }

      runOn(allButVictim: _*) {
        markNodeAsUnavailable(victim)
        within(30 seconds) {
          // victim becomes unreachable
          awaitCond({
            val members = clusterView.members
            clusterView.unreachableMembers.size == 1 &&
              members.size == (roles.size - 1) &&
              members.forall(_.status == MemberStatus.Up)
          })
          awaitSeenSameState(allButVictim map address: _*)
          // still one unreachable
          clusterView.unreachableMembers.size must be(1)
          clusterView.unreachableMembers.head.address must be(node(victim).address)
        }
      }

      endBarrier
    }

    "mark the node as DOWN" taggedAs LongRunningTest in {
      runOn(master) {
        cluster down victim
      }

      runOn(allBut(victim): _*) {
        awaitUpConvergence(roles.size - 1, Seq(victim))
      }

      endBarrier
    }

    "allow node to REJOIN when the network is plugged back in" taggedAs LongRunningTest in {
      runOn(first) {
        // put the network back in
        allBut(victim).foreach { roleName ⇒
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

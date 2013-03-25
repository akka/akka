/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.cluster

import language.postfixOps
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import java.lang.management.ManagementFactory
import javax.management.InstanceNotFoundException
import javax.management.ObjectName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit._
import scala.util.Try

object MBeanMultiJvmSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")
  val fourth = role("fourth")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
    akka.cluster.jmx.enabled = on
    """)).withFallback(MultiNodeClusterSpec.clusterConfig))

}

class MBeanMultiJvmNode1 extends MBeanSpec
class MBeanMultiJvmNode2 extends MBeanSpec
class MBeanMultiJvmNode3 extends MBeanSpec
class MBeanMultiJvmNode4 extends MBeanSpec

abstract class MBeanSpec
  extends MultiNodeSpec(MBeanMultiJvmSpec)
  with MultiNodeClusterSpec {

  import MBeanMultiJvmSpec._
  import ClusterEvent._

  val mbeanName = new ObjectName("akka:type=Cluster")
  lazy val mbeanServer = ManagementFactory.getPlatformMBeanServer

  "Cluster MBean" must {
    "expose attributes" taggedAs LongRunningTest in {
      val info = mbeanServer.getMBeanInfo(mbeanName)
      info.getAttributes.map(_.getName).toSet must be(Set(
        "ClusterStatus", "Members", "Unreachable", "MemberStatus", "Leader", "Singleton", "Available"))
      enterBarrier("after-1")
    }

    "expose operations" taggedAs LongRunningTest in {
      val info = mbeanServer.getMBeanInfo(mbeanName)
      info.getOperations.map(_.getName).toSet must be(Set(
        "join", "leave", "down"))
      enterBarrier("after-2")
    }

    "change attributes after startup" taggedAs LongRunningTest in {
      runOn(first) {
        mbeanServer.getAttribute(mbeanName, "Available").asInstanceOf[Boolean] must be(false)
        mbeanServer.getAttribute(mbeanName, "Singleton").asInstanceOf[Boolean] must be(false)
        mbeanServer.getAttribute(mbeanName, "Leader") must be("")
        mbeanServer.getAttribute(mbeanName, "Members") must be("")
        mbeanServer.getAttribute(mbeanName, "Unreachable") must be("")
        mbeanServer.getAttribute(mbeanName, "MemberStatus") must be("Removed")
      }
      awaitClusterUp(first)
      runOn(first) {
        awaitAssert(mbeanServer.getAttribute(mbeanName, "MemberStatus") must be("Up"))
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Leader") must be(address(first).toString))
        mbeanServer.getAttribute(mbeanName, "Singleton").asInstanceOf[Boolean] must be(true)
        mbeanServer.getAttribute(mbeanName, "Members") must be(address(first).toString)
        mbeanServer.getAttribute(mbeanName, "Unreachable") must be("")
        mbeanServer.getAttribute(mbeanName, "Available").asInstanceOf[Boolean] must be(true)
      }
      enterBarrier("after-3")
    }

    "support join" taggedAs LongRunningTest in {
      runOn(second, third, fourth) {
        mbeanServer.invoke(mbeanName, "join", Array(address(first).toString), Array("java.lang.String"))
      }
      enterBarrier("joined")

      awaitMembersUp(4)
      assertMembers(clusterView.members, roles.map(address(_)): _*)
      awaitAssert(mbeanServer.getAttribute(mbeanName, "MemberStatus") must be("Up"))
      val expectedMembers = roles.sorted.map(address(_)).mkString(",")
      awaitAssert(mbeanServer.getAttribute(mbeanName, "Members") must be(expectedMembers))
      val expectedLeader = address(roleOfLeader())
      awaitAssert(mbeanServer.getAttribute(mbeanName, "Leader") must be(expectedLeader.toString))
      mbeanServer.getAttribute(mbeanName, "Singleton").asInstanceOf[Boolean] must be(false)

      enterBarrier("after-4")
    }

    "support down" taggedAs LongRunningTest in within(20 seconds) {
      val fourthAddress = address(fourth)
      runOn(first) {
        testConductor.shutdown(fourth, 0).await
      }
      enterBarrier("fourth-shutdown")

      runOn(first, second, third) {
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Unreachable") must be(fourthAddress.toString))
        val expectedMembers = Seq(first, second, third).sorted.map(address(_)).mkString(",")
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Members") must be(expectedMembers))
      }
      enterBarrier("fourth-unreachable")

      runOn(second) {
        mbeanServer.invoke(mbeanName, "down", Array(fourthAddress.toString), Array("java.lang.String"))
      }
      enterBarrier("fourth-down")

      runOn(first, second, third) {
        awaitMembersUp(3, canNotBePartOfMemberRing = Set(fourthAddress))
        assertMembers(clusterView.members, first, second, third)
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Unreachable") must be(""))
      }

      enterBarrier("after-5")
    }

    "support leave" taggedAs LongRunningTest in within(20 seconds) {
      runOn(second) {
        mbeanServer.invoke(mbeanName, "leave", Array(address(third).toString), Array("java.lang.String"))
      }
      enterBarrier("third-left")
      runOn(first, second) {
        awaitMembersUp(2)
        assertMembers(clusterView.members, first, second)
        val expectedMembers = Seq(first, second).sorted.map(address(_)).mkString(",")
        awaitAssert(mbeanServer.getAttribute(mbeanName, "Members") must be(expectedMembers))
      }
      runOn(third) {
        awaitCond(cluster.isTerminated)
        // mbean should be unregistered, i.e. throw InstanceNotFoundException
        awaitAssert(intercept[InstanceNotFoundException] {
          mbeanServer.getMBeanInfo(mbeanName)
        })
      }

      enterBarrier("after-6")
    }

  }
}

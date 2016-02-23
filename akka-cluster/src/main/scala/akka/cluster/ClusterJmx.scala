/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster

import java.lang.management.ManagementFactory
import javax.management.StandardMBean
import akka.event.LoggingAdapter
import akka.actor.AddressFromURIString
import javax.management.ObjectName
import javax.management.InstanceAlreadyExistsException
import javax.management.InstanceNotFoundException

/**
 * Interface for the cluster JMX MBean.
 */
trait ClusterNodeMBean {

  /**
   * Member status for this node.
   */
  def getMemberStatus: String

  /**
   * Comma separated addresses of member nodes, sorted in the cluster ring order.
   * The address format is `akka.tcp://actor-system-name@hostname:port`
   */
  def getMembers: String

  /**
   * Comma separated addresses of unreachable member nodes.
   * The address format is `akka.tcp://actor-system-name@hostname:port`
   */
  def getUnreachable: String

  /*
   * JSON format of the status of all nodes in the cluster as follows:
   * {{{
   * {
   *   "self-address": "akka.tcp://system@host1:2552",
   *   "members": [
   *     {
   *       "address": "akka.tcp://system@host1:2552",
   *       "status": "Up",
   *       "roles": [
   *         "frontend"
   *       ]
   *     },
   *     {
   *       "address": "akka.tcp://system@host2:2552",
   *       "status": "Up",
   *       "roles": [
   *         "frontend"
   *       ]
   *     },
   *     {
   *       "address": "akka.tcp://system@host3:2552",
   *       "status": "Down",
   *       "roles": [
   *         "backend"
   *       ]
   *     },
   *     {
   *       "address": "akka.tcp://system@host4:2552",
   *       "status": "Joining",
   *       "roles": [
   *         "backend"
   *       ]
   *     }
   *   ],
   *   "unreachable": [
   *     {
   *       "node": "akka.tcp://system@host2:2552",
   *       "observed-by": [
   *         "akka.tcp://system@host1:2552",
   *         "akka.tcp://system@host3:2552"
   *       ]
   *     },
   *     {
   *       "node": "akka.tcp://system@host3:2552",
   *       "observed-by": [
   *         "akka.tcp://system@host1:2552",
   *         "akka.tcp://system@host2:2552"
   *       ]
   *     }
   *   ]
   * }
   * }}}
   */
  def getClusterStatus: String

  /**
   * Get the address of the current leader.
   * The address format is `akka.tcp://actor-system-name@hostname:port`
   */
  def getLeader: String

  /**
   * Does the cluster consist of only one member?
   */
  def isSingleton: Boolean

  /**
   * Returns true if the node is not unreachable and not `Down`
   * and not `Removed`.
   */
  def isAvailable: Boolean

  /**
   * Try to join this cluster node with the node specified by 'address'.
   * The address format is `akka.tcp://actor-system-name@hostname:port`.
   * A 'Join(thisNodeAddress)' command is sent to the node to join.
   */
  def join(address: String)

  /**
   * Send command to issue state transition to LEAVING for the node specified by 'address'.
   * The address format is `akka.tcp://actor-system-name@hostname:port`
   */
  def leave(address: String)

  /**
   * Send command to DOWN the node specified by 'address'.
   * The address format is `akka.tcp://actor-system-name@hostname:port`
   */
  def down(address: String)
}

/**
 * INTERNAL API
 */
private[akka] class ClusterJmx(cluster: Cluster, log: LoggingAdapter) {

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer
  private val clusterMBeanName = new ObjectName("akka:type=Cluster")
  private def clusterView = cluster.readView
  import cluster.InfoLogger._

  /**
   * Creates the cluster JMX MBean and registers it in the MBean server.
   */
  def createMBean() = {
    val mbean = new StandardMBean(classOf[ClusterNodeMBean]) with ClusterNodeMBean {

      // JMX attributes (bean-style)

      def getClusterStatus: String = {
        val members = clusterView.members.toSeq.sorted(Member.ordering).map { m ⇒
          s"""{
              |      "address": "${m.address}",
              |      "status": "${m.status}",
              |      "roles": [
              |        ${m.roles.map("\"" + _ + "\"").mkString(",\n        ")}
              |      ]
              |    }""".stripMargin
        } mkString (",\n    ")

        val unreachable = clusterView.reachability.observersGroupedByUnreachable.toSeq.sortBy(_._1).map {
          case (subject, observers) ⇒
            s"""{
              |      "node": "${subject.address}",
              |      "observed-by": [
              |        ${observers.toSeq.sorted.map(_.address).mkString("\"", "\",\n        \"", "\"")}
              |      ]
              |    }""".stripMargin
        } mkString (",\n")

        s"""{
        |  "self-address": "${clusterView.selfAddress}",
        |  "members": [
        |    ${members}
        |  ],
        |  "unreachable": [
        |    ${unreachable}
        |  ]
        |}
        |""".stripMargin
      }

      def getMembers: String =
        clusterView.members.toSeq.map(_.address).mkString(",")

      def getUnreachable: String =
        clusterView.unreachableMembers.map(_.address).mkString(",")

      def getMemberStatus: String = clusterView.status.toString

      def getLeader: String = clusterView.leader.fold("")(_.toString)

      def isSingleton: Boolean = clusterView.isSingletonCluster

      def isAvailable: Boolean = clusterView.isAvailable

      // JMX commands

      def join(address: String) = cluster.join(AddressFromURIString(address))

      def leave(address: String) = cluster.leave(AddressFromURIString(address))

      def down(address: String) = cluster.down(AddressFromURIString(address))
    }
    try {
      mBeanServer.registerMBean(mbean, clusterMBeanName)
      logInfo("Registered cluster JMX MBean [{}]", clusterMBeanName)
    } catch {
      case e: InstanceAlreadyExistsException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }

  /**
   * Unregisters the cluster JMX MBean from MBean server.
   */
  def unregisterMBean(): Unit = {
    try {
      mBeanServer.unregisterMBean(clusterMBeanName)
    } catch {
      case e: InstanceNotFoundException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }

}

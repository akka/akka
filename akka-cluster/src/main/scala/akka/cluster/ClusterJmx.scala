/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
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
  def getMemberStatus: String
  def getClusterStatus: String
  def getLeader: String

  def isSingleton: Boolean
  def isConvergence: Boolean
  def isAvailable: Boolean
  def isRunning: Boolean

  def join(address: String)
  def leave(address: String)
  def down(address: String)
}

/**
 * Internal API
 */
private[akka] class ClusterJmx(clusterNode: Cluster, log: LoggingAdapter) {

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer
  private val clusterMBeanName = new ObjectName("akka:type=Cluster")

  /**
   * Creates the cluster JMX MBean and registers it in the MBean server.
   */
  def createMBean() = {
    val mbean = new StandardMBean(classOf[ClusterNodeMBean]) with ClusterNodeMBean {

      // JMX attributes (bean-style)

      /*
       * Sends a string to the JMX client that will list all nodes in the node ring as follows:
       * {{{
       * Members:
       *         Member(address = akka://system0@localhost:5550, status = Up)
       *         Member(address = akka://system1@localhost:5551, status = Up)
       * Unreachable:
       *         Member(address = akka://system2@localhost:5553, status = Down)
       * }}}
       */
      def getClusterStatus: String = {
        val gossip = clusterNode.latestGossip
        val unreachable = gossip.overview.unreachable
        val metaData = gossip.meta
        "\nMembers:\n\t" + gossip.members.mkString("\n\t") +
          { if (unreachable.nonEmpty) "\nUnreachable:\n\t" + unreachable.mkString("\n\t") else "" } +
          { if (metaData.nonEmpty) "\nMeta Data:\t" + metaData.toString else "" }
      }

      def getMemberStatus: String = clusterNode.status.toString

      def getLeader: String = clusterNode.leader.toString

      def isSingleton: Boolean = clusterNode.isSingletonCluster

      def isConvergence: Boolean = clusterNode.convergence.isDefined

      def isAvailable: Boolean = clusterNode.isAvailable

      def isRunning: Boolean = clusterNode.isRunning

      // JMX commands

      def join(address: String) = clusterNode.join(AddressFromURIString(address))

      def leave(address: String) = clusterNode.leave(AddressFromURIString(address))

      def down(address: String) = clusterNode.down(AddressFromURIString(address))
    }
    try {
      mBeanServer.registerMBean(mbean, clusterMBeanName)
      log.info("Cluster Node [{}] - registered cluster JMX MBean [{}]", clusterNode.selfAddress, clusterMBeanName)
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

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
  def isAvailable: Boolean
  def isRunning: Boolean

  def join(address: String)
  def leave(address: String)
  def down(address: String)
}

/**
 * Internal API
 */
private[akka] class ClusterJmx(cluster: Cluster, log: LoggingAdapter) {

  private val mBeanServer = ManagementFactory.getPlatformMBeanServer
  private val clusterMBeanName = new ObjectName("akka:type=Cluster")
  private def clusterView = cluster.readView

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
        val unreachable = clusterView.unreachableMembers
        "\nMembers:\n\t" + clusterView.members.mkString("\n\t") +
          { if (unreachable.nonEmpty) "\nUnreachable:\n\t" + unreachable.mkString("\n\t") else "" }
      }

      def getMemberStatus: String = clusterView.status.toString

      def getLeader: String = clusterView.leader.toString

      def isSingleton: Boolean = clusterView.isSingletonCluster

      def isAvailable: Boolean = clusterView.isAvailable

      def isRunning: Boolean = clusterView.isRunning

      // JMX commands

      def join(address: String) = cluster.join(AddressFromURIString(address))

      def leave(address: String) = cluster.leave(AddressFromURIString(address))

      def down(address: String) = cluster.down(AddressFromURIString(address))
    }
    try {
      mBeanServer.registerMBean(mbean, clusterMBeanName)
      log.info("Cluster Node [{}] - registered cluster JMX MBean [{}]", clusterView.selfAddress, clusterMBeanName)
    } catch {
      case e: InstanceAlreadyExistsException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }

  /**
   * Unregisters the cluster JMX MBean from MBean server.
   */
  def unregisterMBean(): Unit = {
    clusterView.close()
    try {
      mBeanServer.unregisterMBean(clusterMBeanName)
    } catch {
      case e: InstanceNotFoundException ⇒ // ignore - we are running multiple cluster nodes in the same JVM (probably for testing)
    }
  }

}

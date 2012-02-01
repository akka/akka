/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics

import akka.cluster._
import Cluster._
import akka.cluster.zookeeper._
import akka.actor._
import Actor._
import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import java.util.concurrent.{ ConcurrentHashMap, ConcurrentSkipListSet }
import java.util.concurrent.atomic.AtomicReference
import scala.util.Duration
import akka.util.Switch
import akka.util.Helpers._
import scala.util.duration._
import org.I0Itec.zkclient.exception.ZkNoNodeException
import akka.event.EventHandler

/*
 * Instance of the metrics manager running on the node. To keep the fine performance, metrics of all the
 * nodes in the cluster are cached internally, and refreshed from monitoring MBeans / Sigar (when if's local node),
 * of ZooKeeper (if it's metrics of all the nodes in the cluster) after a specified timeout -
 * <code>metricsRefreshTimeout</code>
 * <code>metricsRefreshTimeout</code> defaults to 2 seconds, and can be declaratively defined through
 * akka.conf:
 *
 * @exampl {{{
 *      akka.cluster.metrics-refresh-timeout = 2
 * }}}
 */
class LocalNodeMetricsManager(zkClient: AkkaZkClient, private val metricsRefreshTimeout: Duration)
  extends NodeMetricsManager {

  /*
     * Provides metrics of the system that the node is running on, through monitoring MBeans, Hyperic Sigar
     * and other systems
     */
  lazy private val metricsProvider = SigarMetricsProvider(refreshTimeout.toMillis.toInt) fold ((thrw) ⇒ {
    EventHandler.warning(this, """Hyperic Sigar library failed to load due to %s: %s.
All the metrics will be retreived from monitoring MBeans, and may be incorrect at some platforms.
In order to get better metrics, please put "sigar.jar" to the classpath, and add platform-specific native libary to "java.library.path"."""
      .format(thrw.getClass.getName, thrw.getMessage))
    new JMXMetricsProvider
  },
    sigar ⇒ sigar)

  /*
     *  Metrics of all nodes in the cluster
     */
  private val localNodeMetricsCache = new ConcurrentHashMap[String, NodeMetrics]

  @volatile
  private var _refreshTimeout = metricsRefreshTimeout

  /*
     * Plugged monitors (both local and cluster-wide)
     */
  private val alterationMonitors = new ConcurrentSkipListSet[MetricsAlterationMonitor]

  private val _isRunning = new Switch(false)

  /*
     * If the value is <code>true</code>, metrics manages is started and running. Stopped, otherwise
     */
  def isRunning = _isRunning.isOn

  /*
     * Starts metrics manager. When metrics manager is started, it refreshes cache from ZooKeeper
     * after <code>refreshTimeout</code>, and invokes plugged monitors
     */
  def start() = {
    _isRunning.switchOn { refresh() }
    this
  }

  private[cluster] def metricsForNode(nodeName: String): String = "%s/%s".format(node.NODE_METRICS, nodeName)

  /*
     * Adds monitor that reacts, when specific conditions are satisfied
     */
  def addMonitor(monitor: MetricsAlterationMonitor) = alterationMonitors add monitor

  def removeMonitor(monitor: MetricsAlterationMonitor) = alterationMonitors remove monitor

  def refreshTimeout_=(newValue: Duration) = _refreshTimeout = newValue

  /*
     * Timeout after which metrics, cached in the metrics manager, will be refreshed from ZooKeeper
     */
  def refreshTimeout = _refreshTimeout

  /*
     * Stores metrics of the node in ZooKeeper
     */
  private[akka] def storeMetricsInZK(metrics: NodeMetrics) = {
    val metricsPath = metricsForNode(metrics.nodeName)
    if (zkClient.exists(metricsPath)) {
      zkClient.writeData(metricsPath, metrics)
    } else {
      ignore[ZkNoNodeException](zkClient.createEphemeral(metricsPath, metrics))
    }
  }

  /*
     * Gets metrics of the node from ZooKeeper
     */
  private[akka] def getMetricsFromZK(nodeName: String) = {
    zkClient.readData[NodeMetrics](metricsForNode(nodeName))
  }

  /*
     * Removed metrics of the node from local cache and ZooKeeper
     */
  def removeNodeMetrics(nodeName: String) = {
    val metricsPath = metricsForNode(nodeName)
    if (zkClient.exists(metricsPath)) {
      ignore[ZkNoNodeException](zkClient.delete(metricsPath))
    }

    localNodeMetricsCache.remove(nodeName)
  }

  /*
     * Gets metrics of a local node directly from JMX monitoring beans/Hyperic Sigar
     */
  def getLocalMetrics = metricsProvider.getLocalMetrics

  /*
     * Gets metrics of the node, specified by the name. If <code>useCached</code> is true (default value),
     * metrics snapshot is taken from the local cache; otherwise, it's retreived from ZooKeeper'
     */
  def getMetrics(nodeName: String, useCached: Boolean = true): Option[NodeMetrics] =
    if (useCached)
      Option(localNodeMetricsCache.get(nodeName))
    else
      try {
        Some(getMetricsFromZK(nodeName))
      } catch {
        case ex: ZkNoNodeException ⇒ None
      }

  /*
     * Return metrics of all nodes in the cluster from ZooKeeper
     */
  private[akka] def getAllMetricsFromZK: Map[String, NodeMetrics] = {
    val metricsPaths = zkClient.getChildren(node.NODE_METRICS).toList.toArray.asInstanceOf[Array[String]]
    metricsPaths.flatMap { nodeName ⇒ getMetrics(nodeName, false).map((nodeName, _)) } toMap
  }

  /*
     * Gets cached metrics of all nodes in the cluster
     */
  def getAllMetrics: Array[NodeMetrics] = localNodeMetricsCache.values.asScala.toArray

  /*
     * Refreshes locally cached metrics from ZooKeeper, and invokes plugged monitors
     */
  private[akka] def refresh() {

    storeMetricsInZK(getLocalMetrics)
    refreshMetricsCacheFromZK()

    if (isRunning) {
      Scheduler.schedule({ () ⇒ refresh() }, refreshTimeout.length, refreshTimeout.length, refreshTimeout.unit)
      invokeMonitors()
    }
  }

  /*
     * Refreshes metrics manager cache from ZooKeeper
     */
  private def refreshMetricsCacheFromZK() {
    val allMetricsFromZK = getAllMetricsFromZK

    localNodeMetricsCache.keySet.foreach { key ⇒
      if (!allMetricsFromZK.contains(key))
        localNodeMetricsCache.remove(key)
    }

    // RACY: metrics for the node might have been removed both from ZK and local cache by the moment,
    // but will be re-cached, since they're still present in allMetricsFromZK snapshot. Not important, because
    // cache will be fixed soon, at the next iteration of refresh
    allMetricsFromZK map {
      case (node, metrics) ⇒
        localNodeMetricsCache.put(node, metrics)
    }
  }

  /*
     * Invokes monitors with the cached metrics
     */
  private def invokeMonitors(): Unit = if (!alterationMonitors.isEmpty) {
    // RACY: metrics for some nodes might have been removed/added by that moment. Not important,
    // because monitors will be fed with up-to-date metrics shortly, at the next iteration of refresh
    val clusterNodesMetrics = getAllMetrics
    val localNodeMetrics = clusterNodesMetrics.find(_.nodeName == nodeAddress.nodeName)
    val iterator = alterationMonitors.iterator

    // RACY: there might be new monitors added after the iterator has been obtained. Not important,
    // becuse refresh interval is meant to be very short, and all the new monitors will be called ad the
    // next refresh iteration
    while (iterator.hasNext) {

      val monitor = iterator.next

      monitor match {
        case localMonitor: LocalMetricsAlterationMonitor ⇒
          localNodeMetrics.map { metrics ⇒
            if (localMonitor reactsOn metrics)
              localMonitor react metrics
          }

        case clusterMonitor: ClusterMetricsAlterationMonitor ⇒
          if (clusterMonitor reactsOn clusterNodesMetrics)
            clusterMonitor react clusterNodesMetrics
      }

    }
  }

  def stop() = _isRunning.switchOff

}

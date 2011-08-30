/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics

/*
 * {@link NodeMetricsManager} periodically refershes internal cache with node metrics from MBeans / Sigar.
 * Every time local cache is refreshed, monitors plugged to the metrics manager are invoked.
 * If updated metrics satisfy conditions, specified in <code>reactsOn</code>,
 * <code>react</code> is called
 *
 * @exampl {{{
 * class PeakCPULoadMonitor extends LocalMetricsAlterationMonitor {
 *      val id = "peak-cpu-load-monitor"
 *
 *      def reactsOn(metrics: NodeMetrics) =
 *          metrics.systemLoadAverage > 0.8
 *
 *      def react(metrics: NodeMetrics) =
 *          println("Peak average system load at node [%s] is reached!" format (metrics.nodeName))
 * }
 * }}}
 *
 */
trait LocalMetricsAlterationMonitor extends MetricsAlterationMonitor {

  /*
     * Definies conditions that must be satisfied in order to <code>react<code> on the changed metrics
     */
  def reactsOn(metrics: NodeMetrics): Boolean

  /*
     * Reacts on the changed metrics
     */
  def react(metrics: NodeMetrics): Unit

}

/*
 * {@link NodeMetricsManager} periodically refershes internal cache with metrics of all nodes in the cluster
 * from ZooKeeper. Every time local cache is refreshed, monitors plugged to the metrics manager are invoked.
 * If updated metrics satisfy conditions, specified in <code>reactsOn</code>,
 * <code>react</code> is called
 *
 * @exampl {{{
 * class PeakCPULoadReached extends ClusterMetricsAlterationMonitor {
 *      val id = "peak-cpu-load-reached"
 *
 *      def reactsOn(metrics: Array[NodeMetrics]) =
 *          metrics.forall(_.systemLoadAverage > 0.8)
 *
 *      def react(metrics: Array[NodeMetrics]) =
 *          println("One of the nodes in the scluster has reached the peak system load!")
 * }
 * }}}
 *
 */
trait ClusterMetricsAlterationMonitor extends MetricsAlterationMonitor {

  /*
     * Definies conditions that must be satisfied in order to <code>react<code> on the changed metrics
     */
  def reactsOn(allMetrics: Array[NodeMetrics]): Boolean

  /*
     * Reacts on the changed metrics
     */
  def react(allMetrics: Array[NodeMetrics]): Unit

}

sealed trait MetricsAlterationMonitor extends Comparable[MetricsAlterationMonitor] {

  /*
     * Unique identiifier of the monitor
     */
  def id: String

  def compareTo(otherMonitor: MetricsAlterationMonitor) = id.compareTo(otherMonitor.id)

}

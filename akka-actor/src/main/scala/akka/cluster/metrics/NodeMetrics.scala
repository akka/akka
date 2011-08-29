/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics

/*
 * Snapshot of the JVM / system that's the node is running on
 */
trait NodeMetrics {

  /*
     * Name of the node the metrics are gathered at
     */
  def nodeName: String

  /*
     *  Amount of heap memory currently used
     */
  def usedHeapMemory: Long

  /*
     * Amount of heap memory guaranteed to be available
     */
  def committedHeapMemory: Long

  /*
     * Maximum amount of heap memory that can be used
     */
  def maxHeapMemory: Long

  /*
     * Number of the processors avalable to the JVM
     */
  def avaiableProcessors: Int

  /*
     * If OS-specific Hyperic Sigar library is plugged, it's used to calculate
     * average load on the CPUs in the system. Otherwise, value is retreived from monitoring MBeans.
     * Hyperic Sigar provides more precise values, and, thus, if the library is provided, it's used by default.
     */
  def systemLoadAverage: Double

}

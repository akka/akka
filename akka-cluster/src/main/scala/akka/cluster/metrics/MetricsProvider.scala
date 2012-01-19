/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster.metrics

import akka.cluster._
import akka.event.EventHandler
import java.lang.management.ManagementFactory
import akka.util.ReflectiveAccess._
import akka.util.Switch

/*
 * Snapshot of the JVM / system that's the node is running on
 *
 * @param nodeName name of the node, where metrics are gathered at
 * @param usedHeapMemory amount of heap memory currently used
 * @param committedHeapMemory amount of heap memory guaranteed to be available
 * @param maxHeapMemory maximum amount of heap memory that can be used
 * @param avaiableProcessors number of the processors avalable to the JVM
 * @param systemLoadAverage system load average. If OS-specific Sigar's native library is plugged,
 * it's used to calculate average load on the CPUs in the system. Otherwise, value is retreived from monitoring
 * MBeans. Hyperic Sigar provides more precise values, and, thus, if the library is provided, it's used by default.
 *
 */
case class DefaultNodeMetrics(nodeName: String,
                              usedHeapMemory: Long,
                              committedHeapMemory: Long,
                              maxHeapMemory: Long,
                              avaiableProcessors: Int,
                              systemLoadAverage: Double) extends NodeMetrics

object MetricsProvider {

  /*
     * Maximum value of system load average
     */
  val MAX_SYS_LOAD_AVG = 1

  /*
     * Minimum value of system load average
     */
  val MIN_SYS_LOAD_AVG = 0

  /*
     * Default value of system load average
     */
  val DEF_SYS_LOAD_AVG = 0.5

}

/*
 * Abstracts metrics provider that returns metrics of the system the node is running at
 */
trait MetricsProvider {

  /*
     * Gets metrics of the local system
     */
  def getLocalMetrics: NodeMetrics

}

/*
 * Loads JVM metrics through JMX monitoring beans
 */
class JMXMetricsProvider extends MetricsProvider {

  import MetricsProvider._

  private val memoryMXBean = ManagementFactory.getMemoryMXBean

  private val osMXBean = ManagementFactory.getOperatingSystemMXBean

  /*
     * Validates and calculates system load average
     *
     * @param avg system load average obtained from a specific monitoring provider (may be incorrect)
     * @return system load average, or default value(<code>0.5</code>), if passed value was out of permitted
     * bounds (0.0 to 1.0)
     */
  @inline
  protected final def calcSystemLoadAverage(avg: Double) =
    if (avg >= MIN_SYS_LOAD_AVG && avg <= MAX_SYS_LOAD_AVG) avg else DEF_SYS_LOAD_AVG

  protected def systemLoadAverage = calcSystemLoadAverage(osMXBean.getSystemLoadAverage)

  def getLocalMetrics =
    DefaultNodeMetrics(Cluster.nodeAddress.nodeName,
      memoryMXBean.getHeapMemoryUsage.getUsed,
      memoryMXBean.getHeapMemoryUsage.getCommitted,
      memoryMXBean.getHeapMemoryUsage.getMax,
      osMXBean.getAvailableProcessors,
      systemLoadAverage)

}

/*
 * Loads wider range of metrics of a better quality with Hyperic Sigar (native library)
 *
 * @param refreshTimeout Sigar gathers metrics during this interval
 */
class SigarMetricsProvider private (private val sigarInstance: AnyRef) extends JMXMetricsProvider {

  private val reportErrors = new Switch(true)

  private val getCpuPercMethod = sigarInstance.getClass.getMethod("getCpuPerc")
  private val sigarCpuCombinedMethod = getCpuPercMethod.getReturnType.getMethod("getCombined")

  /*
     * Wraps reflective calls to Hyperic Sigar
     *
     * @param f reflective call to Hyperic Sigar
     * @param fallback function, which is invoked, if call to Sigar has been finished with exception
     */
  private def callSigarMethodOrElse[T](callSigar: ⇒ T, fallback: ⇒ T): T =
    try callSigar catch {
      case thrw ⇒
        reportErrors.switchOff {
          EventHandler.warning(this, "Failed to get metrics from Hyperic Sigar. %s: %s"
            .format(thrw.getClass.getName, thrw.getMessage))
        }
        fallback
    }

  /*
     * Obtains system load average from Sigar
     * If the value cannot be obtained, falls back to system load average taken from JMX
     */
  override def systemLoadAverage = callSigarMethodOrElse(
    calcSystemLoadAverage(sigarCpuCombinedMethod
      .invoke(getCpuPercMethod.invoke(sigarInstance)).asInstanceOf[Double]),
    super.systemLoadAverage)

}

object SigarMetricsProvider {

  /*
     * Instantiates Sigar metrics provider through reflections, in order to avoid creating dependencies to
     * Hiperic Sigar library
     */
  def apply(refreshTimeout: Int): Either[Throwable, MetricsProvider] = try {
    for {
      sigarInstance ← createInstance[AnyRef]("org.hyperic.sigar.Sigar", noParams, noArgs).right
      sigarProxyCacheClass: Class[_] ← getClassFor("org.hyperic.sigar.SigarProxyCache").right
    } yield new SigarMetricsProvider(sigarProxyCacheClass
      .getMethod("newInstance", Array(sigarInstance.getClass, classOf[Int]): _*)
      .invoke(null, sigarInstance, new java.lang.Integer(refreshTimeout)))
  } catch {
    case thrw ⇒ Left(thrw)
  }

}

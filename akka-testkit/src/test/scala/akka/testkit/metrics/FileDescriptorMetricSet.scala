/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.metrics

import java.util
import collection.JavaConverters._
import java.lang.management.{ OperatingSystemMXBean, ManagementFactory }
import com.codahale.metrics.{ Gauge, Metric, MetricSet }
import com.codahale.metrics.MetricRegistry._
import com.codahale.metrics.jvm.FileDescriptorRatioGauge

/**
 * MetricSet exposing number of open and maximum file descriptors used by the JVM process.
 */
private[akka] class FileDescriptorMetricSet(os: OperatingSystemMXBean = ManagementFactory.getOperatingSystemMXBean) extends MetricSet {

  override def getMetrics: util.Map[String, Metric] = {
    Map[String, Metric](

      name("file-descriptors", "open") → new Gauge[Long] {
        override def getValue: Long = invoke("getOpenFileDescriptorCount")
      },

      name("file-descriptors", "max") → new Gauge[Long] {
        override def getValue: Long = invoke("getMaxFileDescriptorCount")
      },

      name("file-descriptors", "ratio") → new FileDescriptorRatioGauge(os)).asJava
  }

  private def invoke(name: String): Long = {
    val method = os.getClass.getDeclaredMethod(name)
    method.setAccessible(true)
    method.invoke(os).asInstanceOf[Long]
  }
}

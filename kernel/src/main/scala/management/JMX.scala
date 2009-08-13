/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.management

import com.twitter.service.Stats

import scala.collection.jcl
import scala.collection.mutable.ArrayBuffer

import java.util.concurrent.ThreadPoolExecutor
import java.lang.management.ManagementFactory
import javax.{management => jmx}
import javax.management.remote.{JMXConnectorServerFactory, JMXServiceURL}

import kernel.Kernel.config
import kernel.util.Logging

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Management extends Logging {
  val RECORD_STATS = config.getBool("akka.management.record-stats", true)
  private var name = "se.scalablesolutions.akka"
  private val mbeanServer = ManagementFactory.getPlatformMBeanServer

  def apply() = {}
  def apply(packageName: String) = name = packageName

  java.rmi.registry.LocateRegistry.createRegistry(1099)
  JMXConnectorServerFactory.newJMXConnectorServer(
    new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi"), 
    null, 
    mbeanServer).start
  
  registerMBean(new StatisticsMBean, "Stats")
  
  def registerMBean(mbean: jmx.DynamicMBean, mbeanType: String) = {
    val objectName = new jmx.ObjectName(name + ":type=" + mbeanType)
    try { mbeanServer.getMBeanInfo(objectName) } catch {
      case e: jmx.InstanceNotFoundException => 
        mbeanServer.registerMBean(mbean, objectName)
    }
  }

  def getStats(reset: Boolean) = {
    var statistics = new ArrayBuffer[Tuple2[String, String]]
    statistics += (("current time", (System.currentTimeMillis / 1000).toString))
    statistics += (("akka version", Kernel.VERSION))
    statistics += (("uptime", Kernel.uptime.toString))
    for ((key, value) <- Stats.getJvmStats) statistics += (key, value.toString)
    for ((key, value) <- Stats.getCounterStats) statistics += (key, value.toString)
    for ((key, value) <- Stats.getTimingStats(reset)) statistics += (key, value.toString)
    for ((key, value) <- Stats.getGaugeStats(reset)) statistics += (key, value.toString)
    val report = {for ((key, value) <- statistics) yield "STAT %s %s".format(key, value)}.mkString("", "\r\n", "\r\n")
    log.info("=========================================\n\t--- Statistics Report ---\n%s=========================================", report)
    report
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class StatisticsMBean extends jmx.DynamicMBean {
  def getMBeanInfo = new jmx.MBeanInfo(
    "se.scalablesolutions.akka.kernel.management.StatisticsMBean", 
    "runtime statistics", 
    getAttributeInfo,
    null, null, null, 
    new jmx.ImmutableDescriptor("immutableInfo=false"))

  def getAttribute(name: String): AnyRef = {
    val segments = name.split("_", 2)
    segments(0) match {
      case "counter" =>
        Stats.getCounterStats()(segments(1)).asInstanceOf[java.lang.Long]
      case "timing" =>
        val prefix = segments(1).split("_", 2)
        val timing = Stats.getTimingStats(false)(prefix(1))
        val x = prefix(0) match {
          case "min" => timing.minimum
          case "max" => timing.maximum
          case "count" => timing.count
          case "average" => timing.average
        }
        x.asInstanceOf[java.lang.Integer]
      case "gauge" =>
        Stats.getGaugeStats(false)(segments(1)).asInstanceOf[java.lang.Double]
    }
  }

  def getAttributes(names: Array[String]): jmx.AttributeList = {
    val rv = new jmx.AttributeList
    for (name <- names) rv.add(new jmx.Attribute(name, getAttribute(name)))
    rv
  }

  def invoke(actionName: String, params: Array[Object], signature: Array[String]): AnyRef =  throw new UnsupportedOperationException
  def setAttribute(attr: jmx.Attribute): Unit = throw new UnsupportedOperationException
  def setAttributes(attrs: jmx.AttributeList): jmx.AttributeList = throw new UnsupportedOperationException

  private def getAttributeInfo: Array[jmx.MBeanAttributeInfo] = {
    (Stats.getCounterStats.keys.map { name =>     
      List(new jmx.MBeanAttributeInfo("counter_" + name, "java.lang.Long", "counter", true, false, false))
    } ++ Stats.getTimingStats(false).keys.map { name =>
      List("min", "max", "average", "count") map { prefix =>
        new jmx.MBeanAttributeInfo("timing_" + prefix + "_" + name, "java.lang.Integer", "timing", true, false, false)
     }
    } ++ Stats.getGaugeStats(false).keys.map { name =>
      List(new jmx.MBeanAttributeInfo("gauge_" + name, "java.lang.Long", "gauge", true, false, false))
    }).toList.flatten[jmx.MBeanAttributeInfo].toArray
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ThreadPoolMBean(threadPool: ThreadPoolExecutor) extends jmx.DynamicMBean {
 val operations: Array[jmx.MBeanOperationInfo] = Array(
    new jmx.MBeanOperationInfo("purge", "",
      Array(), "void", jmx.MBeanOperationInfo.ACTION),
    new jmx.MBeanOperationInfo("shutdown", "",
      Array(), "void", jmx.MBeanOperationInfo.ACTION),
    new jmx.MBeanOperationInfo("setCorePoolSize", "",
      Array(new jmx.MBeanParameterInfo("corePoolSize", "java.lang.Integer", "")), "void", jmx.MBeanOperationInfo.ACTION),
    new jmx.MBeanOperationInfo("setMaximumPoolSize", "",
      Array(new jmx.MBeanParameterInfo("maximumPoolSize", "java.lang.Integer", "")), "void", jmx.MBeanOperationInfo.ACTION),
  )

  def getMBeanInfo = new jmx.MBeanInfo(
    "se.scalablesolutions.akka.kernel.management.ThreadPoolMBean", 
    "runtime management", 
    getAttributeInfo,
    null, operations, null, 
    new jmx.ImmutableDescriptor("immutableInfo=false"))

  def getAttribute(name: String): AnyRef = name match {
    case "getActiveCount" => threadPool.getActiveCount.asInstanceOf[AnyRef]
    case "getCompletedTaskCount" => threadPool.getCompletedTaskCount.asInstanceOf[AnyRef]
    case "getCorePoolSize" => threadPool.getCorePoolSize.asInstanceOf[AnyRef]
    case "getLargestPoolSize" => threadPool.getLargestPoolSize.asInstanceOf[AnyRef]
    case "getMaximumPoolSize" => threadPool.getMaximumPoolSize.asInstanceOf[AnyRef]
    case "getPoolSize" => threadPool.getPoolSize.asInstanceOf[AnyRef]
    case "getTaskCount" => threadPool.getTaskCount.asInstanceOf[AnyRef]
  }

  private def getAttributeInfo: Array[jmx.MBeanAttributeInfo] = {
    Array(
      new jmx.MBeanAttributeInfo("getCorePoolSize", "java.lang.Int", "", true, false, false),
      new jmx.MBeanAttributeInfo("getMaximumPoolSize", "java.lang.Int", "", true, false, false),
      new jmx.MBeanAttributeInfo("getActiveCount", "java.lang.Int", "", true, false, false),
      new jmx.MBeanAttributeInfo("getCompletedTaskCount", "java.lang.Long", "", true, false, false),
      new jmx.MBeanAttributeInfo("getLargestPoolSize", "java.lang.Int", "", true, false, false),
      new jmx.MBeanAttributeInfo("getPoolSize", "java.lang.Int", "", true, false, false),
      new jmx.MBeanAttributeInfo("getTaskCount", "java.lang.Long", "", true, false, false))
  }

  def getAttributes(names: Array[String]): jmx.AttributeList = {
    val rv = new jmx.AttributeList
    for (name <- names) rv.add(new jmx.Attribute(name, getAttribute(name)))
    rv
  }

  def invoke(actionName: String, params: Array[Object], signature: Array[String]): AnyRef = {
    try {
      actionName match {
        case "purge" => threadPool.purge
        case "shutdown" => threadPool.shutdown
        case "setCorePoolSize" =>
          params match {
            case Array(corePoolSize: java.lang.Integer) => threadPool.setCorePoolSize(corePoolSize.intValue)
            case _ => throw new Exception("Bad signature " + params.toList.toString)
          }
        case "setMaximumPoolSize" =>
          params match {
            case Array(maximumPoolSize: java.lang.Integer) => threadPool.setMaximumPoolSize(maximumPoolSize.intValue)
            case _ => throw new Exception("Bad signature " + params.toList.toString)
          }
      }
    } catch { case e: Exception => throw new jmx.MBeanException(e) }
    "Success"
  }

  def setAttribute(attr: jmx.Attribute): Unit = throw new UnsupportedOperationException
  def setAttributes(attrs: jmx.AttributeList): jmx.AttributeList = throw new UnsupportedOperationException
}

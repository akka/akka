/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.management

import com.twitter.service.Stats

import scala.collection.jcl

import java.lang.management.ManagementFactory
import javax.{management => jmx}
import javax.management.remote.{JMXConnectorServerFactory, JMXServiceURL}

import kernel.Kernel.config
import kernel.util.Logging

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Management {
  val RECORD_STATS = config.getBool("akka.management.record-stats", true)

  def startJMX(packageName: String): Unit = 
    startJMX(packageName, ManagementFactory.getPlatformMBeanServer)

  def startJMX(packageName: String, mbeanServer: jmx.MBeanServer): Unit = {
    mbeanServer.registerMBean(new StatisticsMBean, new jmx.ObjectName(packageName + ":type=Stats"))
    mbeanServer.registerMBean(new ManagementMBean, new jmx.ObjectName(packageName + ":type=Management"))
    java.rmi.registry.LocateRegistry.createRegistry(1099)
    JMXConnectorServerFactory.newJMXConnectorServer(
      new JMXServiceURL("service:jmx:rmi:///jndi/rmi://localhost:1099/jmxrmi"), 
      null, 
      mbeanServer).start
  }
}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class StatisticsMBean extends jmx.DynamicMBean with Logging {
  def getMBeanInfo = new jmx.MBeanInfo(
    "se.scalablesolutions.akka.management.Stats", 
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
class ManagementMBean extends jmx.DynamicMBean with Logging {
 val operations: Array[jmx.MBeanOperationInfo] = Array(
    new jmx.MBeanOperationInfo("dosomething", "TODO",
      Array(
        new jmx.MBeanParameterInfo("key", "java.lang.String", "TODO"),
        new jmx.MBeanParameterInfo("value", "java.lang.String", "TODO")
      ), "void", jmx.MBeanOperationInfo.ACTION)
  )

  def getMBeanInfo = new jmx.MBeanInfo(
    "se.scalablesolutions.akka.management.Management", 
    "runtime management", 
    Array[jmx.MBeanAttributeInfo](),
    null, operations, null, 
    new jmx.ImmutableDescriptor("immutableInfo=false"))

  def getAttribute(name: String): AnyRef = throw new UnsupportedOperationException

  def getAttributes(names: Array[String]): jmx.AttributeList = throw new UnsupportedOperationException

  def invoke(actionName: String, params: Array[Object], signature: Array[String]): AnyRef = {
    actionName match {
      case "dosomething" =>
        params match {
          case Array(name: String, value: String) =>
            try {
              {}
            } catch {
              case e: Exception =>
                log.warning("Exception: %s", e.getMessage)
                throw e
            }
          case _ => throw new jmx.MBeanException(new Exception("Bad signature " + params.toList.toString))
        }
      case _ => throw new jmx.MBeanException(new Exception("No such method"))
    }
    null
  }

  def setAttribute(attr: jmx.Attribute): Unit = attr.getValue match {
    case s: String => println("Setting: " + s)
    case _ => throw new jmx.InvalidAttributeValueException()
  }
 
  def setAttributes(attrs: jmx.AttributeList): jmx.AttributeList = {
    for (attr <- jcl.Buffer(attrs.asList)) setAttribute(attr)
    attrs
  }
}

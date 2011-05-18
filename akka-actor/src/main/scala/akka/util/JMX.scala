/**
 *  Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.util

import akka.event.EventHandler

import java.lang.management.ManagementFactory
import javax.management.{ ObjectInstance, ObjectName, InstanceAlreadyExistsException, InstanceNotFoundException }

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object JMX {
  private val mbeanServer = ManagementFactory.getPlatformMBeanServer

  def nameFor(hostname: String, service: String, bean: String): ObjectName =
    new ObjectName("akka.%s:type=%s,name=%s".format(hostname, service, bean.replace(":", "_")))

  def register(name: ObjectName, mbean: AnyRef): Option[ObjectInstance] = try {
    Some(mbeanServer.registerMBean(mbean, name))
  } catch {
    case e: InstanceAlreadyExistsException ⇒
      Some(mbeanServer.getObjectInstance(name))
    case e: Exception ⇒
      EventHandler.error(e, this, "Error when registering mbean [%s]".format(mbean))
      None
  }

  def unregister(mbean: ObjectName) = try {
    mbeanServer.unregisterMBean(mbean)
  } catch {
    case e: InstanceNotFoundException ⇒ {}
    case e: Exception                 ⇒ EventHandler.error(e, this, "Error while unregistering mbean [%s]".format(mbean))
  }
}

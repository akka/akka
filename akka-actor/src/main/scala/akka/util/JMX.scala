/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import akka.event.Logging.Error
import java.lang.management.ManagementFactory
import javax.management.{ ObjectInstance, ObjectName, InstanceAlreadyExistsException, InstanceNotFoundException }
import akka.actor.ActorSystem

object JMX {
  private val mbeanServer = ManagementFactory.getPlatformMBeanServer

  def nameFor(hostname: String, service: String, bean: String): ObjectName =
    new ObjectName("akka.%s:type=%s,name=%s".format(hostname, service, bean.replace(":", "_")))

  def register(name: ObjectName, mbean: AnyRef)(implicit system: ActorSystem): Option[ObjectInstance] = try {
    Some(mbeanServer.registerMBean(mbean, name))
  } catch {
    case e: InstanceAlreadyExistsException ⇒
      Some(mbeanServer.getObjectInstance(name))
    case e: Exception ⇒
      system.eventStream.publish(Error(e, "JMX", this.getClass, "Error when registering mbean [%s]".format(mbean)))
      None
  }

  def unregister(mbean: ObjectName)(implicit system: ActorSystem) = try {
    mbeanServer.unregisterMBean(mbean)
  } catch {
    case e: InstanceNotFoundException ⇒ {}
    case e: Exception                 ⇒ system.eventStream.publish(Error(e, "JMX", this.getClass, "Error while unregistering mbean [%s]".format(mbean)))
  }
}

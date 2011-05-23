/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import AkkaSpringConfigurationTags._

/**
 * Data container for actor configuration data.
 * @author michaelkober
 * @author Martin Krasser
 */
class ActorProperties {
  var id: String = ""
  var typed: String = ""
  var target: String = ""
  var beanRef: String = ""
  var timeoutStr: String = ""
  var interface: String = ""
  var host: String = ""
  var port: String = ""
  var serverManaged: Boolean = false
  var autostart: Boolean = false
  var serviceName: String = ""
  var lifecycle: String = ""
  var scope: String = VAL_SCOPE_SINGLETON
  var dispatcher: DispatcherProperties = _
  var propertyEntries = new PropertyEntries()
  var dependsOn: Array[String] = Array[String]()

  /**
   * Sets the properties to the given builder.
   * @param builder bean definition builder
   */
  def setAsProperties(builder: BeanDefinitionBuilder) {
    builder.addPropertyValue("typed", typed)
    builder.addPropertyValue(HOST, host)
    builder.addPropertyValue(PORT, port)
    builder.addPropertyValue("serverManaged", serverManaged)
    builder.addPropertyValue("serviceName", serviceName)
    builder.addPropertyValue("timeoutStr", timeoutStr)
    builder.addPropertyValue(IMPLEMENTATION, target)
    builder.addPropertyValue("beanRef", beanRef)
    builder.addPropertyValue(INTERFACE, interface)
    builder.addPropertyValue(LIFECYCLE, lifecycle)
    builder.addPropertyValue(SCOPE, scope)
    builder.addPropertyValue(DISPATCHER_TAG, dispatcher)
    builder.addPropertyValue(PROPERTYENTRY_TAG, propertyEntries)
    builder.addPropertyValue("id", id)
    builder.addPropertyValue(AUTOSTART, autostart)
    dependsOn foreach { dep ⇒ builder.addDependsOn(dep) }
  }

  def timeout(): Long = {
    if (!timeoutStr.isEmpty) timeoutStr.toLong else -1L
  }

}

/**
 * Data container for actor configuration data.
 * @author michaelkober
 */
class ActorForProperties {
  var interface: String = ""
  var host: String = ""
  var port: String = ""
  var serviceName: String = ""

  /**
   * Sets the properties to the given builder.
   * @param builder bean definition builder
   */
  def setAsProperties(builder: BeanDefinitionBuilder) {
    builder.addPropertyValue(HOST, host)
    builder.addPropertyValue(PORT, port)
    builder.addPropertyValue("serviceName", serviceName)
    builder.addPropertyValue(INTERFACE, interface)
  }

}

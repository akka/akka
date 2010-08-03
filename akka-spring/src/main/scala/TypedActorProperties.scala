/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import AkkaSpringConfigurationTags._

/**
 * Data container for typed actor configuration data.
 * @author michaelkober
 * @author Martin Krasser
 */
class TypedActorProperties {
  var target: String = ""
  var timeout: Long = _
  var interface: String = ""
  var transactional: Boolean = false
  var host: String = ""
  var port: Int = _
  var lifecycle: String = ""
  var scope:String = VAL_SCOPE_SINGLETON
  var dispatcher: DispatcherProperties = _
  var propertyEntries = new PropertyEntries()


  /**
   * Sets the properties to the given builder.
   * @param builder bean definition builder
   */
  def setAsProperties(builder: BeanDefinitionBuilder) {
    builder.addPropertyValue(HOST, host)
    builder.addPropertyValue(PORT, port)
    builder.addPropertyValue(TIMEOUT, timeout)
    builder.addPropertyValue(IMPLEMENTATION, target)
    builder.addPropertyValue(INTERFACE, interface)
    builder.addPropertyValue(TRANSACTIONAL, transactional)
    builder.addPropertyValue(LIFECYCLE, lifecycle)
    builder.addPropertyValue(SCOPE, scope)
    builder.addPropertyValue(DISPATCHER_TAG, dispatcher)
    builder.addPropertyValue(PROPERTYENTRY_TAG,propertyEntries)
}

}

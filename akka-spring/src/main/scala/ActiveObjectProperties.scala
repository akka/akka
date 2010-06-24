/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.spring

import org.springframework.beans.factory.support.BeanDefinitionBuilder
import AkkaSpringConfigurationTags._

/**
 * Data container for active object configuration data.
 * @author michaelkober
 */
class ActiveObjectProperties {
  var target: String = ""
  var timeout: Long = _
  var interface: String = ""
  var transactional: Boolean = false
  var preRestart: String = ""
  var postRestart: String = ""
  var host: String = ""
  var port: Int = _
  var lifecycle: String = ""
  var scope:String = ""
  var dispatcher: DispatcherProperties = _
  var propertyEntries = new PropertyEntries()


  /**
   * Sets the properties to the given builder.
   * @param builder bean definition builder
   */
  def setAsProperties(builder: BeanDefinitionBuilder) {
    builder.addPropertyValue(HOST, host)
    builder.addPropertyValue(PORT, port)
    builder.addPropertyValue(PRE_RESTART, preRestart)
    builder.addPropertyValue(POST_RESTART, postRestart)
    builder.addPropertyValue(TIMEOUT, timeout)
    builder.addPropertyValue(TARGET, target)
    builder.addPropertyValue(INTERFACE, interface)
    builder.addPropertyValue(TRANSACTIONAL, transactional)
    builder.addPropertyValue(LIFECYCLE, lifecycle)
    builder.addPropertyValue(SCOPE, scope)
    builder.addPropertyValue(DISPATCHER_TAG, dispatcher)
    builder.addPropertyValue(PROPERTYENTRY_TAG,propertyEntries)
}

}

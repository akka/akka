/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.apache.camel.CamelContext
import org.springframework.beans.factory.{DisposableBean, InitializingBean, FactoryBean}
import se.scalablesolutions.akka.camel.{CamelContextManager, CamelService}

/**
 * Factory bean for a {@link CamelService}.
 *
 * @author Martin Krasser
 */
class CamelServiceFactoryBean extends FactoryBean[CamelService] with InitializingBean with DisposableBean {
  @scala.reflect.BeanProperty var camelContext: CamelContext = _

  var instance: CamelService = _

  def isSingleton = true

  def getObjectType = classOf[CamelService]

  def getObject = instance

  /**
   * Initializes the {@link CamelContextManager} with <code>camelService</code> if defined, then
   * creates and starts the {@link CamelService} singleton.
   */
  def afterPropertiesSet = {
    if (camelContext ne null) {
      CamelContextManager.init(camelContext)
    }
    instance = CamelService.newInstance
    instance.load
  }

  /**
   * Stops the {@link CamelService} singleton.
   */
  def destroy = {
    instance.unload
  }
}

/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.core.spi.component.ioc.IoCComponentProviderFactory
import com.sun.jersey.core.spi.component.ComponentContext
import config.ActiveObjectConfigurator

class ActiveObjectComponentProviderFactory(val configurators: List[ActiveObjectConfigurator])
    extends IoCComponentProviderFactory {

  override def getComponentProvider(clazz: Class[_]): ActiveObjectComponentProvider = getComponentProvider(null, clazz)

  override def getComponentProvider(context: ComponentContext, clazz: Class[_]): ActiveObjectComponentProvider = {
    new ActiveObjectComponentProvider(clazz, configurators)
  }
}
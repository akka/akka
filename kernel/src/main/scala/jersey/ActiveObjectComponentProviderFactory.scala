/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.core.spi.component.ioc.{IoCComponentProvider, IoCComponentProviderFactory}
import com.sun.jersey.core.spi.component.{ComponentContext, ComponentProviderFactory}
import config.ActiveObjectConfigurator

class ActiveObjectComponentProviderFactory(val configurator: ActiveObjectConfigurator)
    extends IoCComponentProviderFactory {

  override def getComponentProvider(clazz: Class[_]): ActiveObjectComponentProvider = {
    new ActiveObjectComponentProvider(clazz, configurator)
  }

  override def getComponentProvider(context: ComponentContext, clazz: Class[_]): ActiveObjectComponentProvider = {
    new ActiveObjectComponentProvider(clazz, configurator)
  }
}
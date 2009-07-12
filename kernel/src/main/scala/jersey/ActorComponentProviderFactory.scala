/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.core.spi.component.ioc.IoCComponentProviderFactory
import com.sun.jersey.core.spi.component.ComponentContext

import config.Configurator

class ActorComponentProviderFactory(val configurators: List[Configurator])
    extends IoCComponentProviderFactory {

  override def getComponentProvider(clazz: Class[_]): ActorComponentProvider = getComponentProvider(null, clazz)

  override def getComponentProvider(context: ComponentContext, clazz: Class[_]): ActorComponentProvider = {
    new ActorComponentProvider(clazz, configurators)
  }
}
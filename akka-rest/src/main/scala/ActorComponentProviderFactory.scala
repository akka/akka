/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.rest

import com.sun.jersey.core.spi.component.ioc.{IoCComponentProvider,IoCComponentProviderFactory}
import com.sun.jersey.core.spi.component.{ComponentContext}

import se.scalablesolutions.akka.config.Configurator
import se.scalablesolutions.akka.util.Logging

class ActorComponentProviderFactory(val configurators: List[Configurator])
extends IoCComponentProviderFactory with Logging {
  override def getComponentProvider(clazz: Class[_]): IoCComponentProvider = getComponentProvider(null, clazz)

  override def getComponentProvider(context: ComponentContext, clazz: Class[_]): IoCComponentProvider = {
    configurators.find(_.isDefined(clazz)).map(_ => new ActorComponentProvider(clazz, configurators)).getOrElse(null)
  }
}

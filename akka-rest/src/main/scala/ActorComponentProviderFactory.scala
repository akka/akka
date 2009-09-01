/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.rest

import kernel.Kernel
import util.Logging
import javax.ws.rs.core.Context

import com.sun.jersey.core.spi.component.ioc.{IoCComponentProvider,IoCComponentProviderFactory}
import com.sun.jersey.core.spi.component.{ComponentContext}

import config.Configurator


class ActorComponentProviderFactory(val configurators: List[Configurator])
extends IoCComponentProviderFactory with Logging {
  override def getComponentProvider(clazz: Class[_]): IoCComponentProvider = getComponentProvider(null, clazz)

  override def getComponentProvider(context: ComponentContext, clazz: Class[_]): IoCComponentProvider = {
    configurators.find(_.isDefined(clazz)).map(_ => new ActorComponentProvider(clazz, configurators)).getOrElse(null)
  }
}

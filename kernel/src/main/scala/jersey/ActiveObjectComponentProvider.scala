/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import kernel.Logging
import config.ActiveObjectConfigurator

import com.sun.jersey.core.spi.component.ioc.IoCComponentProvider
import com.sun.jersey.core.spi.component.ComponentProvider

import java.lang.reflect.{Constructor, InvocationTargetException}

class ActiveObjectComponentProvider(val clazz: Class[_], val configurator: ActiveObjectConfigurator)
    extends IoCComponentProvider with Logging {

  override def getInstance: AnyRef = {
    configurator.getActiveObject(clazz).asInstanceOf[AnyRef]
  }
}
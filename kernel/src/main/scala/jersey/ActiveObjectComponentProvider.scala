/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.core.spi.component.ioc.{IoCManagedComponentProvider, IoCFullyManagedComponentProvider, IoCInstantiatedComponentProvider, IoCComponentProvider}
import kernel.Logging
import config.ActiveObjectConfigurator

import com.sun.jersey.core.spi.component.ComponentProvider

import java.lang.reflect.{Constructor, InvocationTargetException}

class ActiveObjectComponentProvider(val clazz: Class[_], val configurator: ActiveObjectConfigurator)
    extends IoCManagedComponentProvider with Logging {

  override def getInstance: AnyRef =
    configurator.getActiveObject(clazz).asInstanceOf[AnyRef]

  override def getInjectableInstance(obj: AnyRef): AnyRef = {
    obj.asInstanceOf[ActiveObjectProxy].targetInstance
  }
}
/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.core.spi.component.ioc.IoCFullyManagedComponentProvider

import kernel.config.ActiveObjectConfigurator
import kernel.util.Logging
import java.lang.reflect.{Constructor, InvocationTargetException}

class ActiveObjectComponentProvider(val clazz: Class[_], val configurator: ActiveObjectConfigurator)
    extends IoCFullyManagedComponentProvider with Logging {

  override def getInstance: AnyRef = configurator.getActiveObject(clazz).asInstanceOf[AnyRef]
}
/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel.jersey

import com.sun.jersey.core.spi.component.ioc.IoCFullyManagedComponentProvider

import kernel.config.ActiveObjectConfigurator
import kernel.util.Logging

class ActiveObjectComponentProvider(val clazz: Class[_], val configurators: List[ActiveObjectConfigurator])
    extends IoCFullyManagedComponentProvider with Logging {

  override def getInstance: AnyRef = {
    val instances = for {
      conf <- configurators
      if conf.isActiveObjectDefined(clazz)
    } yield conf.getActiveObject(clazz).asInstanceOf[AnyRef]
    instances match {
      case instance :: Nil => instance
      case Nil => throw new IllegalArgumentException("No Active Object for class [" +  clazz + "] could be found. Make sure you have defined and configured the class as an Active Object in a ActiveObjectConfigurator")
      case _ => throw new IllegalArgumentException("Active Object for class [" +  clazz + "] is defined in more than one ActiveObjectConfigurator. Eliminate the redundancy.")
    }
  }
}
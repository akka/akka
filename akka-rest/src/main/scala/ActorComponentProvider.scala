/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.rest

import com.sun.jersey.core.spi.component.ComponentScope
import com.sun.jersey.core.spi.component.ioc.IoCFullyManagedComponentProvider

import se.scalablesolutions.akka.config.Configurator
import se.scalablesolutions.akka.util.Logging

class ActorComponentProvider(val clazz: Class[_], val configurators: List[Configurator])
    extends IoCFullyManagedComponentProvider with Logging {
   
  override def getScope = ComponentScope.Singleton

  override def getInstance: AnyRef = {
    val instances = for {
      conf <- configurators
      if conf.isDefined(clazz)
    } yield conf.getInstance(clazz).asInstanceOf[AnyRef]
    instances match {
      case instance :: Nil => instance
      case Nil => throw new IllegalArgumentException("No Actor for class [" +  clazz + "] could be found. Make sure you have defined and configured the class as an Active Object or Actor in a Configurator")
      case _ => throw new IllegalArgumentException("Actor for class [" +  clazz + "] is defined in more than one Configurator. Eliminate the redundancy.")
    }
  }
}
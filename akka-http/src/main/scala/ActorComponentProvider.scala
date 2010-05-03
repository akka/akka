/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.rest

import com.sun.jersey.core.spi.component.ComponentScope
import com.sun.jersey.core.spi.component.ioc.IoCFullyManagedComponentProvider

import se.scalablesolutions.akka.config.Configurator
import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.actor.Actor

class ActorComponentProvider(val clazz: Class[_], val configurators: List[Configurator])
    extends IoCFullyManagedComponentProvider with Logging {
   
  override def getScope = ComponentScope.Singleton

  override def getInstance: AnyRef = {
    val instances = for {
      conf <- configurators
      if conf.isDefined(clazz)
      instance <- conf.getInstance(clazz)
    } yield instance
    if (instances.isEmpty) throw new IllegalArgumentException(
      "No Actor or Active Object for class [" +  clazz + "] could be found.\nMake sure you have defined and configured the class as an Active Object or Actor in a supervisor hierarchy.")
    else instances.head.asInstanceOf[AnyRef]
  }
}
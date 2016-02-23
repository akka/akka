/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.camel

import akka.actor.ExtendedActorSystem
import org.apache.camel.impl.DefaultCamelContext

/**
 * Implement this interface in order to inject a specific CamelContext in a system
 * An instance of this class must be instantiable using a no-arg constructor.
 */
trait ContextProvider {
  /**
   * Retrieve or create a Camel Context for the given actor system
   * Called once per actor system
   */
  def getContext(system: ExtendedActorSystem): DefaultCamelContext
}

/**
 * Default implementation of [[akka.camel.ContextProvider]]
 * Provides a new DefaultCamelContext per actor system
 */
final class DefaultContextProvider extends ContextProvider {
  override def getContext(system: ExtendedActorSystem) = new DefaultCamelContext
}
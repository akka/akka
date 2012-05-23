/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import org.apache.camel.model.ProcessorDefinition

package object camel {
  //TODO Why do I exist?
  implicit def toActorRouteDefinition(definition: ProcessorDefinition[_]) = new ActorRouteDefinition(definition)
}
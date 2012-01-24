/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka

import org.apache.camel.model.RouteDefinition

package object camel {
  implicit def toActorRouteDefinition(rd: RouteDefinition) = new ActorRouteDefinition(rd)
}
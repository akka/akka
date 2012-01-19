package akka

import org.apache.camel.model.RouteDefinition

package object camel{
  implicit def toActorRouteDefinition(rd: RouteDefinition) = new ActorRouteDefinition(rd)
}
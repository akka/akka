package akka.camel

import internal.component.ActorEndpointPath
import org.apache.camel.model.RouteDefinition
import akka.actor.ActorRef

object  ActorRouteDefinition {
  implicit def toActorRouteDefinition(rd: RouteDefinition) = new ActorRouteDefinition(rd)
}

class ActorRouteDefinition(rd: RouteDefinition){
  def to(actorRef: ActorRef) = rd.to(ActorEndpointPath(actorRef).toCamelPath())
  def to(actorRef: ActorRef, conf: ConsumerConfig) = rd.to(ActorEndpointPath(actorRef).toCamelPath(conf))
}



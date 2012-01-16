package akka.camel

import org.apache.camel.model.RouteDefinition
import akka.actor.ActorRef

object  ActorRouteDefinition {
  implicit def toActorRouteDefinition(rd: RouteDefinition) = new ActorRouteDefinition(rd)
}

class ActorRouteDefinition(rd: RouteDefinition){
  def to(actorRef: ActorRef) = rd.to("actor://path:%s" format actorRef.path)
}



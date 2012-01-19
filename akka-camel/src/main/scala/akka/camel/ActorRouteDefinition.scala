/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel

import internal.component.ActorEndpointPath
import akka.actor.ActorRef
import org.apache.camel.model.ProcessorDefinition

class ActorRouteDefinition(definition: ProcessorDefinition[_]) {
  def to(actorRef: ActorRef) = definition.to(ActorEndpointPath(actorRef).toCamelPath())
  def to(actorRef: ActorRef, consumerConfig: ConsumerConfig) = definition.to(ActorEndpointPath(actorRef).toCamelPath(consumerConfig))
}


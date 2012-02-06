/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camel.internal.component

import org.scalatest.matchers.MustMatchers
import akka.util.duration._
import akka.camel.TestSupport.SharedCamelSystem
import org.apache.camel.Component
import org.scalatest.WordSpec

class ActorComponentConfigurationTest extends WordSpec with MustMatchers with SharedCamelSystem {

  val component: Component = camel.context.getComponent("actor")

  "Endpoint url config must be correctly parsed" in {
    val actorEndpointConfig = component.createEndpoint("actor://path:akka://test/user/$a?autoack=false&replyTimeout=987000000+nanos").asInstanceOf[ActorEndpointConfig]

    actorEndpointConfig must have(
      'endpointUri("actor://path:akka://test/user/$a?autoack=false&replyTimeout=987000000+nanos"),
      'path(ActorEndpointPath.fromCamelPath("path:akka://test/user/$a")),
      'autoack(false),
      'replyTimeout(987000000 nanos))
  }

}
/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.camel.internal.component

import language.postfixOps

import org.scalatest.Matchers
import scala.concurrent.duration._
import akka.camel.TestSupport.SharedCamelSystem
import org.apache.camel.Component
import org.scalatest.WordSpec

class ActorComponentConfigurationTest extends WordSpec with Matchers with SharedCamelSystem {

  val component: Component = camel.context.getComponent("akka")

  "Endpoint url config should be correctly parsed" in {
    val actorEndpointConfig = component.createEndpoint(s"akka://test/user/$$a?autoAck=false&replyTimeout=987000000+nanos").asInstanceOf[ActorEndpointConfig]

    actorEndpointConfig should have(
      'endpointUri(s"akka://test/user/$$a?autoAck=false&replyTimeout=987000000+nanos"),
      'path(ActorEndpointPath.fromCamelPath(s"akka://test/user/$$a")),
      'autoAck(false),
      'replyTimeout(987000000 nanos))
  }

}

package akka.camel.internal.component


import org.junit._
import org.scalatest.junit.JUnitSuite

import org.scalatest.matchers.ShouldMatchers
import akka.util.duration._
import akka.camel.Blocking
import akka.camel.TestSupport.SharedCamelSystem
import org.apache.camel.Component

class ActorComponentConfigurationTest extends JUnitSuite  with ShouldMatchers with SharedCamelSystem{

  val component: Component = camel.context.getComponent("actor")

  @Test def configurationSanityTest = {
    val actorEndpointConfig = component.createEndpoint("actor://path:akka://test/user/$a?autoack=false&communicationStyle=Blocking%28123000000+nanos%29&outTimeout=987000000+nanos").asInstanceOf[ActorEndpointConfig]

    actorEndpointConfig should  have (
      'endpointUri ("actor://path:akka://test/user/$a?autoack=false&communicationStyle=Blocking%28123000000+nanos%29&outTimeout=987000000+nanos"),
      'path (ActorEndpointPath.fromCamelPath("path:akka://test/user/$a")),
      'communicationStyle (Blocking(123000000 nanos)),
      'autoack (false),
      'outTimeout (987000000 nanos)
    )
  }

}
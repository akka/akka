package se.scalablesolutions.akka.camel.component

import org.apache.camel.impl.DefaultCamelContext
import org.junit._
import org.scalatest.junit.JUnitSuite

class ActorComponentTest extends JUnitSuite {

  val component: ActorComponent = ActorComponentTest.mockComponent

  @Test def shouldCreateEndpointWithIdDefined = {
    val ep1: ActorEndpoint = component.createEndpoint("actor:abc").asInstanceOf[ActorEndpoint]
    val ep2: ActorEndpoint = component.createEndpoint("actor:id:abc").asInstanceOf[ActorEndpoint]
    assert(ep1.id === Some("abc"))
    assert(ep2.id === Some("abc"))
    assert(ep1.uuid === None)
    assert(ep2.uuid === None)
  }

  @Test def shouldCreateEndpointWithUuidDefined = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:abc").asInstanceOf[ActorEndpoint]
    assert(ep.uuid === Some("abc"))
    assert(ep.id === None)
  }
}

object ActorComponentTest {
  def mockComponent = {
    val component = new ActorComponent
    component.setCamelContext(new DefaultCamelContext)
    component
  }

  def mockEndpoint(uri:String) = mockComponent.createEndpoint(uri)
}

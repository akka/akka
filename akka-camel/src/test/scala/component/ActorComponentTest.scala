package se.scalablesolutions.akka.camel.component

import org.apache.camel.{Endpoint, AsyncProcessor}
import org.apache.camel.impl.DefaultCamelContext
import org.junit._
import org.scalatest.junit.JUnitSuite

import se.scalablesolutions.akka.actor.uuidFrom

class ActorComponentTest extends JUnitSuite {
  val component: ActorComponent = ActorComponentTest.actorComponent

  def testUUID = uuidFrom("93da8c80-c3fd-11df-abed-60334b120057")

  @Test def shouldCreateEndpointWithIdDefined = {
    val ep1: ActorEndpoint = component.createEndpoint("actor:abc").asInstanceOf[ActorEndpoint]
    val ep2: ActorEndpoint = component.createEndpoint("actor:id:abc").asInstanceOf[ActorEndpoint]
    assert(ep1.id === Some("abc"))
    assert(ep2.id === Some("abc"))
    assert(ep1.uuid === None)
    assert(ep2.uuid === None)
    assert(!ep1.blocking)
    assert(!ep2.blocking)
  }

  @Test def shouldCreateEndpointWithUuidDefined = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:" + testUUID).asInstanceOf[ActorEndpoint]
    assert(ep.uuid === Some(testUUID))
    assert(ep.id === None)
    assert(!ep.blocking)
  }

  @Test def shouldCreateEndpointWithBlockingSet = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:"+testUUID+"?blocking=true").asInstanceOf[ActorEndpoint]
    assert(ep.uuid === Some(testUUID))
    assert(ep.id === None)
    assert(ep.blocking)
  }
}

object ActorComponentTest {
  def actorComponent = {
    val component = new ActorComponent
    component.setCamelContext(new DefaultCamelContext)
    component
  }

  def actorEndpoint(uri:String) = actorComponent.createEndpoint(uri)
  def actorProducer(endpoint: Endpoint) = endpoint.createProducer
  def actorAsyncProducer(endpoint: Endpoint) = endpoint.createProducer.asInstanceOf[AsyncProcessor]
}

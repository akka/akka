package akka.camel.component

import org.apache.camel.{Endpoint, AsyncProcessor}
import org.apache.camel.impl.DefaultCamelContext
import org.junit._
import org.scalatest.junit.JUnitSuite

import akka.actor.uuidFrom

class ActorComponentTest extends JUnitSuite {
  val component: ActorComponent = ActorComponentTest.actorComponent

  def testUUID = "93da8c80-c3fd-11df-abed-60334b120057"

  @Test def shouldCreateEndpointWithIdDefined = {
    val ep1: ActorEndpoint = component.createEndpoint("actor:abc").asInstanceOf[ActorEndpoint]
    val ep2: ActorEndpoint = component.createEndpoint("actor:id:abc").asInstanceOf[ActorEndpoint]
    assert(ep1.idValue === Some("abc"))
    assert(ep2.idValue === Some("abc"))
    assert(ep1.idType === "id")
    assert(ep2.idType === "id")
    assert(!ep1.blocking)
    assert(!ep2.blocking)
    assert(ep1.autoack)
    assert(ep2.autoack)
  }

  @Test def shouldCreateEndpointWithIdTemplate = {
    val ep: ActorEndpoint = component.createEndpoint("actor:id:").asInstanceOf[ActorEndpoint]
    assert(ep.idValue === None)
    assert(ep.idType === "id")
    assert(!ep.blocking)
    assert(ep.autoack)
  }

  @Test def shouldCreateEndpointWithIdTemplateAndBlockingSet = {
    val ep: ActorEndpoint = component.createEndpoint("actor:id:?blocking=true").asInstanceOf[ActorEndpoint]
    assert(ep.idValue === None)
    assert(ep.idType === "id")
    assert(ep.blocking)
    assert(ep.autoack)
  }

  @Test def shouldCreateEndpointWithUuidDefined = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:%s" format testUUID).asInstanceOf[ActorEndpoint]
    assert(ep.idValue === Some(testUUID))
    assert(ep.idType === "uuid")
    assert(!ep.blocking)
    assert(ep.autoack)
  }

  @Test def shouldCreateEndpointWithUuidTemplate = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:").asInstanceOf[ActorEndpoint]
    assert(ep.idValue === None)
    assert(ep.idType === "uuid")
    assert(!ep.blocking)
    assert(ep.autoack)
  }

  @Test def shouldCreateEndpointWithUuidTemplateAndBlockingSet = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:?blocking=true").asInstanceOf[ActorEndpoint]
    assert(ep.idValue === None)
    assert(ep.idType === "uuid")
    assert(ep.blocking)
    assert(ep.autoack)
  }

  @Test def shouldCreateEndpointWithBlockingSet = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:%s?blocking=true" format testUUID).asInstanceOf[ActorEndpoint]
    assert(ep.idValue === Some(testUUID))
    assert(ep.idType === "uuid")
    assert(ep.blocking)
    assert(ep.autoack)
  }

  @Test def shouldCreateEndpointWithAutoackUnset = {
    val ep: ActorEndpoint = component.createEndpoint("actor:uuid:%s?autoack=false" format testUUID).asInstanceOf[ActorEndpoint]
    assert(ep.idValue === Some(testUUID))
    assert(ep.idType === "uuid")
    assert(!ep.blocking)
    assert(!ep.autoack)
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

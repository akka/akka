package se.scalablesolutions.akka.actor

import org.junit.Assert._
import org.scalatest.junit.JUnitSuite
import org.junit.Test

class ActorRegistryTest extends JUnitSuite {

  val registry = ActorRegistry

  @Test
  def testRegistrationWithDefaultId {
    val actor = new TestActor1
    assertEquals(actor.getClass.getName, actor.getId)
    testRegistration(actor, classOf[TestActor1])
  }

  @Test
  def testRegistrationWithCustomId {
    val actor = new TestActor2
    assertEquals("customid", actor.getId)
    testRegistration(actor, classOf[TestActor2])
  }

  private def testRegistration[T <: Actor](actor: T, actorClass: Class[T]) {
    assertEquals("non-started actor registered", Nil, registry.actorsFor(actorClass))
    assertEquals("non-started actor registered", Nil, registry.actorsFor(actor.getId))
    assertEquals("non-started actor registered", None, registry.actorFor(actor.uuid))
    actor.start
    assertEquals("actor not registered", List(actor), registry.actorsFor(actorClass))
    assertEquals("actor not registered", List(actor), registry.actorsFor(actor.getId))
    assertEquals("actor not registered", Some(actor), registry.actorFor(actor.uuid))
    actor.stop
    assertEquals("stopped actor registered", Nil, registry.actorsFor(actorClass))
    assertEquals("stopped actor registered", Nil, registry.actorsFor(actor.getId))
    assertEquals("stopped actor registered", None, registry.actorFor(actor.uuid))
  }

}

class TestActor1 extends Actor {

  // use default id

  protected def receive = null

}

class TestActor2 extends Actor {

  id = "customid"

  protected def receive = null

}
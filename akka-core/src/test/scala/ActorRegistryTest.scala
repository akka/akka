package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

/*
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

 */
class ActorRegistryTest extends JUnitSuite {
  var record = ""
  class TestActor extends Actor {
    id = "MyID"
    def receive = {
      case "ping" =>
        record = "pong" + record
    }
  }

  @Test def shouldGetActorByIdFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor = new TestActor
    actor.start
    val actors = ActorRegistry.actorsFor("MyID")
    assert(actors.size === 1)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId == "MyID")
    actor.stop
  }

  @Test def shouldGetActorByUUIDFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor = new TestActor
    val uuid = actor.uuid
    actor.start
    val actorOrNone = ActorRegistry.actorFor(uuid)
    assert(actorOrNone.isDefined)
    assert(actorOrNone.get.uuid === uuid)
    actor.stop
  }

  @Test def shouldGetActorByClassFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor = new TestActor
    actor.start
    val actors = ActorRegistry.actorsFor(classOf[TestActor])
    assert(actors.size === 1)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId === "MyID")
    actor.stop
  }

  @Test def shouldGetActorByManifestFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor = new TestActor
    actor.start
    val actors: List[TestActor] = ActorRegistry.actorsFor[TestActor]
    assert(actors.size === 1)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId === "MyID")
    actor.stop
  }

  @Test def shouldGetActorsByIdFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    val actors = ActorRegistry.actorsFor("MyID")
    assert(actors.size === 2)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId === "MyID")
    assert(actors.last.isInstanceOf[TestActor])
    assert(actors.last.getId === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetActorsByClassFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    val actors = ActorRegistry.actorsFor(classOf[TestActor])
    assert(actors.size === 2)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId === "MyID")
    assert(actors.last.isInstanceOf[TestActor])
    assert(actors.last.getId === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetActorsByManifestFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    val actors: List[TestActor] = ActorRegistry.actorsFor[TestActor]
    assert(actors.size === 2)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId === "MyID")
    assert(actors.last.isInstanceOf[TestActor])
    assert(actors.last.getId === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetAllActorsFromActorRegistry = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    val actors = ActorRegistry.actors
    assert(actors.size === 2)
    assert(actors.head.isInstanceOf[TestActor])
    assert(actors.head.getId === "MyID")
    assert(actors.last.isInstanceOf[TestActor])
    assert(actors.last.getId === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetResponseByAllActorsInActorRegistryWhenInvokingForeach = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    record = ""
    ActorRegistry.foreach(actor => actor send "ping")
    Thread.sleep(1000)
    assert(record === "pongpong")
    actor1.stop
    actor2.stop
  }

  @Test def shouldShutdownAllActorsInActorRegistry = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    ActorRegistry.shutdownAll
    assert(ActorRegistry.actors.size === 0)
  }

  @Test def shouldRemoveUnregisterActorInActorRegistry = {
    ActorRegistry.shutdownAll
    val actor1 = new TestActor
    actor1.start
    val actor2 = new TestActor
    actor2.start
    assert(ActorRegistry.actors.size === 2)
    ActorRegistry.unregister(actor1)
    assert(ActorRegistry.actors.size === 1)
    ActorRegistry.unregister(actor2)
    assert(ActorRegistry.actors.size === 0)
  }
}

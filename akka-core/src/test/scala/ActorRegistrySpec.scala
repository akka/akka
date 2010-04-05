package se.scalablesolutions.akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

class ActorRegistrySpec extends JUnitSuite {
  var record = ""
  class TestActor extends Actor {
    id = "MyID"
    def receive = {
      case "ping" =>
        record = "pong" + record
        reply("got ping")
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
    ActorRegistry.foreach(actor => actor !! "ping")
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

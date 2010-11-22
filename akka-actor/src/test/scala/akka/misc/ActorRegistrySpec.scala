package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import Actor._
import java.util.concurrent.{CyclicBarrier, TimeUnit, CountDownLatch}

object ActorRegistrySpec {
  var record = ""
  class TestActor extends Actor {
    self.id = "MyID"
    def receive = {
      case "ping" =>
        record = "pong" + record
        self.reply("got ping")
    }
  }

  class TestActor2 extends Actor {
    self.id = "MyID2"
    def receive = {
      case "ping" =>
        record = "pong" + record
        self.reply("got ping")
      case "ping2" =>
        record = "pong" + record
        self.reply("got ping")
    }
  }
}

class ActorRegistrySpec extends JUnitSuite {
  import ActorRegistrySpec._

  @Test def shouldGetActorByIdFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor = actorOf[TestActor]
    actor.start
    val actors = ActorRegistry.actorsFor("MyID")
    assert(actors.size === 1)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    actor.stop
  }

  @Test def shouldGetActorByUUIDFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor = actorOf[TestActor]
    val uuid = actor.uuid
    actor.start
    val actorOrNone = ActorRegistry.actorFor(uuid)
    assert(actorOrNone.isDefined)
    assert(actorOrNone.get.uuid === uuid)
    actor.stop
  }

  @Test def shouldGetActorByClassFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor = actorOf[TestActor]
    actor.start
    val actors = ActorRegistry.actorsFor(classOf[TestActor])
    assert(actors.size === 1)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    actor.stop
  }

  @Test def shouldGetActorByManifestFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor = actorOf[TestActor]
    actor.start
    val actors = ActorRegistry.actorsFor[TestActor]
    assert(actors.size === 1)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    actor.stop
  }

  @Test def shouldFindThingsFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor = actorOf[TestActor]
    actor.start
    val found = ActorRegistry.find({ case a: ActorRef if a.actor.isInstanceOf[TestActor] => a })
    assert(found.isDefined)
    assert(found.get.actor.isInstanceOf[TestActor])
    assert(found.get.id === "MyID")
    actor.stop
  }

  @Test def shouldGetActorsByIdFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    val actors = ActorRegistry.actorsFor("MyID")
    assert(actors.size === 2)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    assert(actors.last.actor.isInstanceOf[TestActor])
    assert(actors.last.id === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetActorsByClassFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    val actors = ActorRegistry.actorsFor(classOf[TestActor])
    assert(actors.size === 2)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    assert(actors.last.actor.isInstanceOf[TestActor])
    assert(actors.last.id === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetActorsByManifestFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    val actors = ActorRegistry.actorsFor[TestActor]
    assert(actors.size === 2)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    assert(actors.last.actor.isInstanceOf[TestActor])
    assert(actors.last.id === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetActorsByMessageFromActorRegistry {

    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor2]
    actor2.start

    val actorsForAcotrTestActor = ActorRegistry.actorsFor[TestActor]
    assert(actorsForAcotrTestActor.size === 1)

    val actorsForAcotrTestActor2 = ActorRegistry.actorsFor[TestActor2]
    assert(actorsForAcotrTestActor2.size === 1)

    val actorsForAcotr = ActorRegistry.actorsFor[Actor]
    assert(actorsForAcotr.size === 2)


    val actorsForMessagePing2 = ActorRegistry.actorsFor[Actor]("ping2")
    assert(actorsForMessagePing2.size === 1)

    val actorsForMessagePing = ActorRegistry.actorsFor[Actor]("ping")
    assert(actorsForMessagePing.size === 2)

    actor1.stop
    actor2.stop
  }

  @Test def shouldGetAllActorsFromActorRegistry {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    val actors = ActorRegistry.actors
    assert(actors.size === 2)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.id === "MyID")
    assert(actors.last.actor.isInstanceOf[TestActor])
    assert(actors.last.id === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetResponseByAllActorsInActorRegistryWhenInvokingForeach {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    record = ""
    ActorRegistry.foreach(actor => actor !! "ping")
    assert(record === "pongpong")
    actor1.stop
    actor2.stop
  }

  @Test def shouldShutdownAllActorsInActorRegistry {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    ActorRegistry.shutdownAll
    assert(ActorRegistry.actors.size === 0)
  }

  @Test def shouldRemoveUnregisterActorInActorRegistry {
    ActorRegistry.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    assert(ActorRegistry.actors.size === 2)
    ActorRegistry.unregister(actor1)
    assert(ActorRegistry.actors.size === 1)
    ActorRegistry.unregister(actor2)
    assert(ActorRegistry.actors.size === 0)
  }

  @Test def shouldBeAbleToRegisterActorsConcurrently {
    ActorRegistry.shutdownAll

    def mkTestActors = for(i <- (1 to 10).toList;j <- 1 to 3000) yield actorOf( new Actor {
      self.id = i.toString
      def receive = { case _ => }
    })

    val latch = new CountDownLatch(3)
    val barrier = new CyclicBarrier(3)

    def mkThread(actors: Iterable[ActorRef]) = new Thread {
      this.start
      override def run {
        barrier.await
        actors foreach { _.start }
        latch.countDown
      }
    }
    val a1,a2,a3 = mkTestActors
    val t1 = mkThread(a1)
    val t2 = mkThread(a2)
    val t3 = mkThread(a3)


    assert(latch.await(30,TimeUnit.SECONDS) === true)

    for(i <- 1 to 10) {
      val theId = i.toString
      val actors = ActorRegistry.actorsFor(theId).toSet
      for(a <- actors if a.id == theId) assert(actors contains a)
      assert(actors.size === 9000)
    }
  }
}

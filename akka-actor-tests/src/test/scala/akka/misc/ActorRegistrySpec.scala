package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import Actor._
import java.util.concurrent.{CyclicBarrier, TimeUnit, CountDownLatch}

object ActorRegistrySpec {
  var record = ""
  class TestActor extends Actor {
    self.address = "MyID"
    def receive = {
      case "ping" =>
        record = "pong" + record
        self.reply("got ping")
    }
  }

  class TestActor2 extends Actor {
    self.address = "MyID2"
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

  @Test def shouldGetActorByAddressFromActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = Actor.registry.actorFor(actor1.address)
    assert(actor2.isDefined)
    assert(actor2.get.address === actor1.address)
    actor2.get.stop
  }

  @Test def shouldGetActorByUUIDFromLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor = actorOf[TestActor]
    val uuid = actor.uuid
    actor.start
    val actorOrNone = Actor.registry.local.actorFor(uuid)
    assert(actorOrNone.isDefined)
    assert(actorOrNone.get.uuid === uuid)
    actor.stop
  }

  @Test def shouldFindThingsFromLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor = actorOf[TestActor]
    actor.start
    val found = Actor.registry.local.find({ case a: ActorRef if a.actor.isInstanceOf[TestActor] => a })
    assert(found.isDefined)
    assert(found.get.actor.isInstanceOf[TestActor])
    assert(found.get.address === "MyID")
    actor.stop
  }

  @Test def shouldGetAllActorsFromLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    val actors = Actor.registry.local.actors
    assert(actors.size === 2)
    assert(actors.head.actor.isInstanceOf[TestActor])
    assert(actors.head.address === "MyID")
    assert(actors.last.actor.isInstanceOf[TestActor])
    assert(actors.last.address === "MyID")
    actor1.stop
    actor2.stop
  }

  @Test def shouldGetResponseByAllActorsInLocalActorRegistryWhenInvokingForeach {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    record = ""
    Actor.registry.local.foreach(actor => actor !! "ping")
    assert(record === "pongpong")
    actor1.stop
    actor2.stop
  }

  @Test def shouldShutdownAllActorsInLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    Actor.registry.local.shutdownAll
    assert(Actor.registry.local.actors.size === 0)
  }

  @Test def shouldRemoveUnregisterActorInLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]
    actor1.start
    val actor2 = actorOf[TestActor]
    actor2.start
    assert(Actor.registry.local.actors.size === 2)
    Actor.registry.unregister(actor1)
    assert(Actor.registry.local.actors.size === 1)
    Actor.registry.unregister(actor2)
    assert(Actor.registry.local.actors.size === 0)
  }

  /*
  @Test def shouldBeAbleToRegisterActorsConcurrently {
    Actor.registry.local.shutdownAll

    def mkTestActors = for(i <- (1 to 10).toList;j <- 1 to 3000) yield actorOf( new Actor {
      self.address = i.toString
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
      val actor = Actor.registry.local.actorFor(theId)
      assert(actor eq a)
      assert(actors.size === 9000)
    }
  }
  */
}

package akka.actor

import org.scalatest.junit.JUnitSuite
import org.scalatest.BeforeAndAfterAll
import org.junit.Test
import Actor._
import org.scalatest.Assertions._
import java.util.concurrent.{ ConcurrentLinkedQueue, CyclicBarrier, TimeUnit, CountDownLatch }
import akka.dispatch.Future

object ActorRegistrySpec {
  class TestActor extends Actor {
    def receive = {
      case "ping" ⇒
        self.reply("got ping")
    }
  }

  class TestActor2 extends Actor {
    def receive = {
      case "ping" ⇒
        self.reply("got ping")
      case "ping2" ⇒
        self.reply("got ping")
    }
  }
}

class ActorRegistrySpec extends JUnitSuite with BeforeAndAfterAll {
  import ActorRegistrySpec._

  override def afterAll = {
    akka.event.EventHandler.start
  }

  @Test
  def shouldGetActorByAddressFromActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]("test-actor-1")
    actor1.start
    val actor2 = Actor.registry.actorFor(actor1.address)
    assert(actor2.isDefined)
    assert(actor2.get.address === actor1.address)
    assert(actor2.get.address === "test-actor-1")
    actor2.get.stop
    assert(Actor.registry.actorFor(actor1.address).isEmpty)
  }

  @Test
  def shouldGetActorByUUIDFromLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor = actorOf[TestActor]("test-actor-1")
    val uuid = actor.uuid
    actor.start
    val actorOrNone = Actor.registry.local.actorFor(uuid)
    assert(actorOrNone.isDefined)
    assert(actorOrNone.get.uuid === uuid)
    assert(actorOrNone.get.address === "test-actor-1")
    actor.stop
    assert(Actor.registry.local.actorFor(uuid).isEmpty)
  }

  @Test
  def shouldFindThingsFromLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor = actorOf[TestActor]("test-actor-1").start()
    val found: Option[LocalActorRef] = Actor.registry.local.find({ case a: LocalActorRef if a.actorInstance.get().isInstanceOf[TestActor] ⇒ a })
    assert(found.isDefined)
    assert(found.get.actorInstance.get().isInstanceOf[TestActor])
    assert(found.get.address === "test-actor-1")
    actor.stop
  }

  @Test
  def shouldGetAllActorsFromLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]("test-actor-1").start()
    val actor2 = actorOf[TestActor]("test-actor-2").start()
    val actors = Actor.registry.local.actors
    assert(actors.size === 2)
    assert(actors.head.asInstanceOf[LocalActorRef].actorInstance.get().isInstanceOf[TestActor])
    assert(actors.head.address === "test-actor-2")
    assert(actors.last.asInstanceOf[LocalActorRef].actorInstance.get().isInstanceOf[TestActor])
    assert(actors.last.address === "test-actor-1")
    actor1.stop
    actor2.stop
  }

  @Test
  def shouldGetResponseByAllActorsInLocalActorRegistryWhenInvokingForeach {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]("test-actor-1").start
    val actor2 = actorOf[TestActor]("test-actor-2").start
    val results = new ConcurrentLinkedQueue[Future[String]]

    Actor.registry.local.foreach(actor ⇒ results.add(actor.?("ping").mapTo[String]))

    assert(results.size === 2)
    val i = results.iterator
    while (i.hasNext) assert(i.next.get === "got ping")
    actor1.stop()
    actor2.stop()
  }

  @Test
  def shouldShutdownAllActorsInLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]("test-actor-1")
    actor1.start
    val actor2 = actorOf[TestActor]("test-actor-2")
    actor2.start
    Actor.registry.local.shutdownAll
    assert(Actor.registry.local.actors.size === 0)
  }

  @Test
  def shouldRemoveUnregisterActorInLocalActorRegistry {
    Actor.registry.local.shutdownAll
    val actor1 = actorOf[TestActor]("test-actor-1")
    actor1.start
    val actor2 = actorOf[TestActor]("test-actor-2")
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
      this.start()
      override def run {
        barrier.await
        actors foreach { _.start() }
        latch.countDown()
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

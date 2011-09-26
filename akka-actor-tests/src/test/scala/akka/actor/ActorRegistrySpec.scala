package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import akka.testkit._
import Actor._
import java.util.concurrent.{ ConcurrentLinkedQueue, CyclicBarrier, TimeUnit, CountDownLatch }
import akka.dispatch.Future

object ActorRegistrySpec {

  class TestActor extends Actor {
    def receive = {
      case "ping" ⇒ reply("got ping")
    }
  }

  class StartStopTestActor(startedLatch: TestLatch, stoppedLatch: TestLatch) extends Actor {
    override def preStart = {
      startedLatch.countDown
    }

    def receive = {
      case "ping" ⇒ reply("got ping")
    }

    override def postStop = {
      stoppedLatch.countDown
    }
  }
}

class ActorRegistrySpec extends WordSpec with MustMatchers with BeforeAndAfterAll with BeforeAndAfterEach {
  import ActorRegistrySpec._

  override def afterAll = {
    Actor.registry.local.shutdownAll
    akka.event.EventHandler.start()
  }

  override def beforeEach = {
    Actor.registry.local.shutdownAll
  }

  "Actor Registry" must {

    /* "get actor by address from registry" in {
      val started = TestLatch(1)
      val stopped = TestLatch(1)
      val actor = actorOf(new StartStopTestActor(started, stopped), "test-actor-1")
      started.await()
      val registered = Actor.registry.actorFor(actor.address)
      registered.isDefined must be(true)
      registered.get.address must be(actor.address)
      registered.get.address must be("test-actor-1")
      registered.get.stop
      stopped.await
      Actor.registry.actorFor(actor.address).isEmpty must be(true)
    }

    "get actor by uuid from local registry" in {
      val started = TestLatch(1)
      val stopped = TestLatch(1)
      val actor = actorOf(new StartStopTestActor(started, stopped), "test-actor-1")
      started.await
      val uuid = actor.uuid
      val registered = Actor.registry.local.actorFor(uuid)
      registered.isDefined must be(true)
      registered.get.uuid must be(uuid)
      registered.get.address must be("test-actor-1")
      actor.stop
      stopped.await
      Actor.registry.local.actorFor(uuid).isEmpty must be(true)
    }

    "find things from local registry" in {
      val actor = actorOf[TestActor]("test-actor-1")
      val found: Option[LocalActorRef] = Actor.registry.local.find({ case a: LocalActorRef if a.underlyingActorInstance.isInstanceOf[TestActor] ⇒ a })
      found.isDefined must be(true)
      found.get.underlyingActorInstance.isInstanceOf[TestActor] must be(true)
      found.get.address must be("test-actor-1")
      actor.stop
    }

    "get all actors from local registry" in {
      val actor1 = actorOf[TestActor]("test-actor-1")
      val actor2 = actorOf[TestActor]("test-actor-2")
      val actors = Actor.registry.local.actors
      actors.size must be(2)
      actors.find(_.address == "test-actor-2").get.asInstanceOf[LocalActorRef].underlyingActorInstance.isInstanceOf[TestActor] must be(true)
      actors.find(_.address == "test-actor-1").get.asInstanceOf[LocalActorRef].underlyingActorInstance.isInstanceOf[TestActor] must be(true)
      actor1.stop
      actor2.stop
    } */

    "get response from all actors in local registry using foreach" in {
      val actor1 = actorOf[TestActor]("test-actor-1")
      val actor2 = actorOf[TestActor]("test-actor-2")
      val results = new ConcurrentLinkedQueue[Future[String]]

      Actor.registry.local.foreach(actor ⇒ results.add(actor.?("ping").mapTo[String]))

      results.size must be(2)
      val i = results.iterator
      while (i.hasNext) assert(i.next.get === "got ping")
      actor1.stop()
      actor2.stop()
    }
    /*
    "shutdown all actors in local registry" in {
      val actor1 = actorOf[TestActor]("test-actor-1")
      val actor2 = actorOf[TestActor]("test-actor-2")
      Actor.registry.local.shutdownAll
      Actor.registry.local.actors.size must be(0)
    }

    "remove when unregistering actors from local registry" in {
      val actor1 = actorOf[TestActor]("test-actor-1")
      val actor2 = actorOf[TestActor]("test-actor-2")
      Actor.registry.local.actors.size must be(2)
      Actor.registry.unregister(actor1)
      Actor.registry.local.actors.size must be(1)
      Actor.registry.unregister(actor2)
      Actor.registry.local.actors.size must be(0)
    } */
  }
}

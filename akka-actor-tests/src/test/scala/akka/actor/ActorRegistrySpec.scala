// package akka.actor
// 
// import org.scalatest.junit.JUnitSuite
// import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
// import org.junit.Test
// import Actor._
// import org.scalatest.Assertions._
// import java.util.concurrent.{ ConcurrentLinkedQueue, CyclicBarrier, TimeUnit, CountDownLatch }
// import akka.dispatch.Future
// 
// object ActorRegistrySpec {
//   class TestActor extends Actor {
//     def receive = {
//       case "ping" ⇒
//         reply("got ping")
//     }
//   }
// 
//   class TestActor2 extends Actor {
//     def receive = {
//       case "ping" ⇒
//         reply("got ping")
//       case "ping2" ⇒
//         reply("got ping")
//     }
//   }
// }
// 
// class ActorRegistrySpec extends JUnitSuite with BeforeAndAfterAll {
//   import ActorRegistrySpec._
// 
//   override def afterAll = {
//     akka.event.EventHandler.start()
//   }
// 
//   @Test
//   def shouldGetActorByAddressFromActorRegistry {
//     Actor.registry.local.shutdownAll
//     val actor1 = actorOf[TestActor]("test-actor-1")
//     val actor2 = Actor.registry.actorFor(actor1.address)
//     assert(actor2.isDefined)
//     assert(actor2.get.address === actor1.address)
//     assert(actor2.get.address === "test-actor-1")
//     actor2.get.stop
//     assert(Actor.registry.actorFor(actor1.address).isEmpty)
//   }
// 
//   @Test
//   def shouldGetActorByUUIDFromLocalActorRegistry {
//     Actor.registry.local.shutdownAll
//     val actor = actorOf[TestActor]("test-actor-1")
//     val uuid = actor.uuid
//     val actorOrNone = Actor.registry.local.actorFor(uuid)
//     assert(actorOrNone.isDefined)
//     assert(actorOrNone.get.uuid === uuid)
//     assert(actorOrNone.get.address === "test-actor-1")
//     actor.stop
//     assert(Actor.registry.local.actorFor(uuid).isEmpty)
//   }
// 
//   @Test
//   def shouldFindThingsFromLocalActorRegistry {
//     Actor.registry.local.shutdownAll
//     val actor = actorOf[TestActor]("test-actor-1")
//     val found: Option[LocalActorRef] = Actor.registry.local.find({ case a: LocalActorRef if a.underlyingActorInstance.isInstanceOf[TestActor] ⇒ a })
//     assert(found.isDefined)
//     assert(found.get.underlyingActorInstance.isInstanceOf[TestActor])
//     assert(found.get.address === "test-actor-1")
//     actor.stop
//   }
// 
//   @Test
//   def shouldGetAllActorsFromLocalActorRegistry {
//     Actor.registry.local.shutdownAll
//     val actor1 = actorOf[TestActor]("test-actor-1")
//     val actor2 = actorOf[TestActor]("test-actor-2")
//     val actors = Actor.registry.local.actors
//     assert(actors.size === 2)
//     assert(actors.find(_.address == "test-actor-2").get.asInstanceOf[LocalActorRef].underlyingActorInstance.isInstanceOf[TestActor])
//     assert(actors.find(_.address == "test-actor-1").get.asInstanceOf[LocalActorRef].underlyingActorInstance.isInstanceOf[TestActor])
//     actor1.stop
//     actor2.stop
//   }
// 
//   @Test
//   def shouldGetResponseByAllActorsInLocalActorRegistryWhenInvokingForeach {
//     Actor.registry.local.shutdownAll
//     val actor1 = actorOf[TestActor]("test-actor-1")
//     val actor2 = actorOf[TestActor]("test-actor-2")
//     val results = new ConcurrentLinkedQueue[Future[String]]
// 
//     Actor.registry.local.foreach(actor ⇒ results.add(actor.?("ping").mapTo[String]))
// 
//     assert(results.size === 2)
//     val i = results.iterator
//     while (i.hasNext) assert(i.next.get === "got ping")
//     actor1.stop()
//     actor2.stop()
//   }
// 
//   @Test
//   def shouldShutdownAllActorsInLocalActorRegistry {
//     Actor.registry.local.shutdownAll
//     val actor1 = actorOf[TestActor]("test-actor-1")
//     val actor2 = actorOf[TestActor]("test-actor-2")
//     Actor.registry.local.shutdownAll
//     assert(Actor.registry.local.actors.size === 0)
//   }
// 
//   @Test
//   def shouldRemoveUnregisterActorInLocalActorRegistry {
//     Actor.registry.local.shutdownAll
//     val actor1 = actorOf[TestActor]("test-actor-1")
//     val actor2 = actorOf[TestActor]("test-actor-2")
//     assert(Actor.registry.local.actors.size === 2)
//     Actor.registry.unregister(actor1)
//     assert(Actor.registry.local.actors.size === 1)
//     Actor.registry.unregister(actor2)
//     assert(Actor.registry.local.actors.size === 0)
//   }
// }

/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.{ Spec, WordSpec }
import org.scalatest.Assertions
import org.scalatest.matchers.{ ShouldMatchers, MustMatchers }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.japi.Option

import TypedActorSpec._
import akka.dispatch.Promise._
import akka.dispatch.{ Promise, Future, DefaultCompletableFuture }

object TypedActorSpec {
  trait MyTypedActor {
    def tell(msg: String): Unit
    def sendRequestReply(msg: String): String
  }

  class MyTypedActorImpl extends TypedActor with MyTypedActor {
    self.id = "my-custom-id"
    def tell(msg: String) {
      println("got " + msg)
    }
    def sendRequestReply(msg: String): String = {
      "got " + msg
    }
  }

  class MyTypedActorWithConstructorArgsImpl(aString: String, aLong: Long) extends TypedActor with MyTypedActor {
    self.id = "my-custom-id"
    def tell(msg: String) {
      println("got " + msg + " " + aString + " " + aLong)
    }

    def sendRequestReply(msg: String): String = {
      msg + " " + aString + " " + aLong
    }
  }

  class MyActor extends Actor {
    self.id = "my-custom-id"
    def receive = {
      case msg: String ⇒ println("got " + msg)
    }
  }

}

@RunWith(classOf[JUnitRunner])
class TypedActorSpec extends Spec with ShouldMatchers with BeforeAndAfterEach {

  var simplePojo: SimpleJavaPojo = null
  var pojo: MyTypedActor = null;

  override def beforeEach() {
    simplePojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
    pojo = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyTypedActorImpl])
  }

  override def afterEach() {
    Actor.registry.shutdownAll()
  }

  describe("TypedActor") {

    it("should return POJO method return value when invoked") {
      val result = simplePojo.hello("POJO")
      result should equal("Hello POJO")
    }

    it("should resolve Future return from method defined to return a Future") {
      val future = simplePojo.square(10)
      future.await
      future.result.isDefined should equal(true)
      future.result.get should equal(100)
    }

    it("should return none instead of exception") {
      val someVal = Option.some("foo")
      val noneVal = Option.none[String]
      val nullVal = null: Option[String]

      assert(simplePojo.passThru(someVal) === someVal)
      assert(simplePojo.passThru(noneVal) === Option.some(null))
      assert(simplePojo.passThru(nullVal) === Option.some(null))
    }

    it("should accept constructor arguments") {
      val pojo1 = TypedActor.newInstance(classOf[MyTypedActor], new MyTypedActorWithConstructorArgsImpl("test", 1L))
      assert(pojo1.sendRequestReply("hello") === "hello test 1")

      val pojo2 = TypedActor.newInstance(classOf[MyTypedActor], new MyTypedActorWithConstructorArgsImpl("test2", 2L), new TypedActorConfiguration())
      assert(pojo2.sendRequestReply("hello") === "hello test2 2")

      val pojo3 = TypedActor.newInstance(classOf[MyTypedActor], new MyTypedActorWithConstructorArgsImpl("test3", 3L), 5000L)
      assert(pojo3.sendRequestReply("hello") === "hello test3 3")
    }

    it("should handle onComplete-callbacks for Future-returning methods") {
      val gotStopped = Promise[Boolean](10000)
      val pojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      val ref = TypedActor.actorFor(pojo).get
      val result: Future[java.lang.Integer] = pojo.square(10)
      result.onComplete(_ ⇒ {
        TypedActor.stop(pojo)
        gotStopped completeWithResult ref.isShutdown
      })
      result.get should equal(100)
      ref.isShutdown should equal(true)
      gotStopped.get should equal(true)
    }
  }

  describe("TypedActor object") {
    it("should support finding the underlying actor for a given proxy and the proxy for a given actor") {
      val typedActorRef = TypedActor.actorFor(simplePojo).get
      val typedActor = typedActorRef.actor.asInstanceOf[TypedActor]
      assert(typedActor.proxy === simplePojo)
      assert(TypedActor.proxyFor(typedActorRef).get === simplePojo)
    }
  }

  describe("ActorRegistry") {
    it("should support finding a typed actor by uuid ") {
      val typedActorRef = TypedActor.actorFor(simplePojo).get
      val uuid = typedActorRef.uuid
      assert(Actor.registry.typedActorFor(newUuid()) === None)
      assert(Actor.registry.typedActorFor(uuid).isDefined)
      assert(Actor.registry.typedActorFor(uuid).get === simplePojo)
    }

    it("should support finding typed actors by id ") {
      val typedActors = Actor.registry.typedActorsFor("my-custom-id")
      assert(typedActors.length === 1)
      assert(typedActors.contains(pojo))

      // creating untyped actor with same custom id
      val actorRef = Actor.actorOf[MyActor].start()
      val typedActors2 = Actor.registry.typedActorsFor("my-custom-id")
      assert(typedActors2.length === 1)
      assert(typedActors2.contains(pojo))
      actorRef.stop()
    }

    it("should support to filter typed actors") {
      val actors = Actor.registry.filterTypedActors(ta ⇒ ta.isInstanceOf[MyTypedActor])
      assert(actors.length === 1)
      assert(actors.contains(pojo))
    }

    it("should support to find typed actors by class") {
      val actors = Actor.registry.typedActorsFor(classOf[MyTypedActorImpl])
      assert(actors.length === 1)
      assert(actors.contains(pojo))
      assert(Actor.registry.typedActorsFor(classOf[MyActor]).isEmpty)
    }

    it("should support to get all typed actors") {
      val actors = Actor.registry.typedActors
      assert(actors.length === 2)
      assert(actors.contains(pojo))
      assert(actors.contains(simplePojo))
    }

    it("should support to find typed actors by manifest") {
      val actors = Actor.registry.typedActorsFor[MyTypedActorImpl]
      assert(actors.length === 1)
      assert(actors.contains(pojo))
      assert(Actor.registry.typedActorsFor[MyActor].isEmpty)
    }

    it("should support foreach for typed actors") {
      val actorRef = Actor.actorOf[MyActor].start()
      assert(Actor.registry.actors.size === 3)
      assert(Actor.registry.typedActors.size === 2)
      Actor.registry.foreachTypedActor(TypedActor.stop(_))
      assert(Actor.registry.actors.size === 1)
      assert(Actor.registry.typedActors.size === 0)
    }

    it("should shutdown all typed and untyped actors") {
      val actorRef = Actor.actorOf[MyActor].start()
      assert(Actor.registry.actors.size === 3)
      assert(Actor.registry.typedActors.size === 2)
      Actor.registry.shutdownAll()
      assert(Actor.registry.actors.size === 0)
      assert(Actor.registry.typedActors.size === 0)
    }
  }
}
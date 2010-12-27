/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.actor

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.japi.Option;

import akka.dispatch.DefaultCompletableFuture
import TypedActorSpec._


object TypedActorSpec {
  trait MyTypedActor {
    def sendOneWay(msg: String) : Unit
    def sendRequestReply(msg: String) : String
  }

  class MyTypedActorImpl extends TypedActor with MyTypedActor {
    self.id = "my-custom-id"
    def sendOneWay(msg: String) {
      println("got " + msg )
    }
    def sendRequestReply(msg: String) : String = {
      "got " + msg
    }
  }

  class MyTypedActorWithConstructorArgsImpl(aString: String, aLong: Long) extends TypedActor with MyTypedActor {
    self.id = "my-custom-id"
    def sendOneWay(msg: String) {
      println("got " + msg + " " + aString + " " + aLong)
    }

    def sendRequestReply(msg: String) : String = {
      msg + " " + aString + " " + aLong
    }
  }

  class MyActor extends Actor {
    self.id = "my-custom-id"
    def receive = {
        case msg: String => println("got " + msg)
    }
  }

}


@RunWith(classOf[JUnitRunner])
class TypedActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterEach {

  var simplePojo: SimpleJavaPojo = null
  var pojo: MyTypedActor = null;

  override def beforeEach() {
    simplePojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
    pojo = TypedActor.newInstance(classOf[MyTypedActor], classOf[MyTypedActorImpl])
  }

  override def afterEach() {
    ActorRegistry.shutdownAll
  }

  describe("TypedActor") {

    it("should resolve Future return from method defined to return a Future") {
      val future = simplePojo.square(10)
      future.await
      future.result.isDefined should equal (true)
      future.result.get should equal (100)
    }

    it("should return none instead of exception") {
      val someVal = Option.some("foo")
      val noneVal = Option.none[String]
      val nullVal = null:Option[String]

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
      assert(ActorRegistry.typedActorFor(newUuid()) === None)
      assert(ActorRegistry.typedActorFor(uuid).isDefined)
      assert(ActorRegistry.typedActorFor(uuid).get === simplePojo)
    }

    it("should support finding typed actors by id ") {
      val typedActors = ActorRegistry.typedActorsFor("my-custom-id")
      assert(typedActors.length === 1)
      assert(typedActors.contains(pojo))

      // creating untyped actor with same custom id
      val actorRef = Actor.actorOf[MyActor].start
      val typedActors2 = ActorRegistry.typedActorsFor("my-custom-id")
      assert(typedActors2.length === 1)
      assert(typedActors2.contains(pojo))
      actorRef.stop
    }

    it("should support to filter typed actors") {
      val actors = ActorRegistry.filterTypedActors(ta => ta.isInstanceOf[MyTypedActor])
      assert(actors.length === 1)
      assert(actors.contains(pojo))
    }

    it("should support to find typed actors by class") {
      val actors = ActorRegistry.typedActorsFor(classOf[MyTypedActorImpl])
      assert(actors.length === 1)
      assert(actors.contains(pojo))
      assert(ActorRegistry.typedActorsFor(classOf[MyActor]).isEmpty)
    }

    it("should support to get all typed actors") {
      val actors = ActorRegistry.typedActors
      assert(actors.length === 2)
      assert(actors.contains(pojo))
      assert(actors.contains(simplePojo))
    }

    it("should support to find typed actors by manifest") {
      val actors = ActorRegistry.typedActorsFor[MyTypedActorImpl]
      assert(actors.length === 1)
      assert(actors.contains(pojo))
      assert(ActorRegistry.typedActorsFor[MyActor].isEmpty)
    }

    it("should support foreach for typed actors") {
      val actorRef = Actor.actorOf[MyActor].start
      assert(ActorRegistry.actors.size === 3)
      assert(ActorRegistry.typedActors.size === 2)
      ActorRegistry.foreachTypedActor(TypedActor.stop(_))
      assert(ActorRegistry.actors.size === 1)
      assert(ActorRegistry.typedActors.size === 0)
    }

    it("should shutdown all typed and untyped actors") {
      val actorRef = Actor.actorOf[MyActor].start
      assert(ActorRegistry.actors.size === 3)
      assert(ActorRegistry.typedActors.size === 2)
      ActorRegistry.shutdownAll()
      assert(ActorRegistry.actors.size === 0)
      assert(ActorRegistry.typedActors.size === 0)
    }
  }
}

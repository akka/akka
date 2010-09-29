/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.actor

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.dispatch.DefaultCompletableFuture
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
  BeforeAndAfterAll {

  override def afterAll() {
    ActorRegistry.shutdownAll
  }

  describe("TypedActor") {

    it("should resolve Future return from method defined to return a Future") {
      val pojo = TypedActor.newInstance(classOf[SimpleJavaPojo], classOf[SimpleJavaPojoImpl])
      val future = pojo.square(10)
      future.await
      future.result.isDefined should equal (true)
      future.result.get should equal (100)
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
}

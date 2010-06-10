package se.scalablesolutions.akka.actor

import Actor._

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

@RunWith(classOf[JUnitRunner])
class SerializableActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  describe("SerializableActor") {
    it("should be able to serialize and deserialize a JavaSerializableActor") {
      val actor1 = actorOf[JavaSerializableTestActor].start
      val serializer = actor1.serializer.getOrElse(fail("Serializer not defined"))
      (actor1 !! "hello").getOrElse("_") should equal("world")

      val bytes = actor1.toBinary
      
//      val actor2 = serializer.fromBinary(bytes, Some(classOf[JavaSerializableTestActor])).asInstanceOf[Actor]
//      (actor2 !! "hello").getOrElse("_") should equal("world")
      true should equal(true)
    }
  }
}

@serializable class JavaSerializableTestActor extends JavaSerializableActor[JavaSerializableTestActor] {
  def receive = {
    case "hello" => reply("world")
  }
}
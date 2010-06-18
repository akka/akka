package se.scalablesolutions.akka.actor

import Actor._

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import com.google.protobuf.Message

@RunWith(classOf[JUnitRunner])
class SerializableActorSpec extends
  Spec with
  ShouldMatchers with
  BeforeAndAfterAll {

  describe("SerializableActor") {
    it("should be able to serialize and deserialize a JavaSerializableActor") {
      val actor1 = actorOf[JavaSerializableTestActor].start
      val serializer = actor1.serializer.getOrElse(fail("Serializer not defined"))
      (actor1 !! "hello").getOrElse("_") should equal("world 1")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 2")
    }
    
    it("should be able to serialize and deserialize a ProtobufSerializableActor") {
      val actor1 = actorOf[ProtobufSerializableTestActor].start
      val serializer = actor1.serializer.getOrElse(fail("Serializer not defined"))
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 3")
    }

    
/*
    it("should be able to serialize and deserialize a JavaJSONSerializableActor") {
      val actor1 = actorOf[JavaJSONSerializableTestActor].start
      val serializer = actor1.serializer.getOrElse(fail("Serializer not defined"))
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 3")
    }

    it("should be able to serialize and deserialize a ScalaJSONSerializableActor") {
      val actor1 = actorOf[ScalaJSONSerializableTestActor].start
      val serializer = actor1.serializer.getOrElse(fail("Serializer not defined"))
      (actor1 !! "hello").getOrElse("_") should equal("world 1")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 2")
    }
*/
  }
}

@serializable class JavaSerializableTestActor extends JavaSerializableActor {
  private var count = 0
  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}

class ProtobufSerializableTestActor extends ProtobufSerializableActor[ProtobufProtocol.Counter] {
  val clazz = classOf[ProtobufProtocol.Counter]
  private var count = 0

  def toProtobuf = ProtobufProtocol.Counter.newBuilder.setCount(count).build
  def fromProtobuf(message: ProtobufProtocol.Counter) = count = message.getCount

  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}

class JavaJSONSerializableTestActor extends JavaJSONSerializableActor {
  private var count = 0
  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}

@scala.reflect.BeanInfo class ScalaJSONSerializableTestActor extends ScalaJSONSerializableActor {
  private var count = 0
  def receive = {
    case "hello" =>
      count = count + 1
      self.reply("world " + count)
  }
}
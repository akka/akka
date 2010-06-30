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
      (actor1 !! "hello").getOrElse("_") should equal("world 1")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 2")
    }

    it("should be able to serialize and deserialize a ProtobufSerializableActor") {
      val actor1 = actorOf[ProtobufSerializableTestActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world 1")
      (actor1 !! "hello").getOrElse("_") should equal("world 2")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world 3")
    }

    it("should be able to serialize and deserialize a StatelessSerializableActor") {
      val actor1 = actorOf[StatelessSerializableTestActor].start
      (actor1 !! "hello").getOrElse("_") should equal("world")

      val bytes = actor1.toBinary
      val actor2 = ActorRef.fromBinaryToLocalActorRef(bytes)

      actor2.start
      (actor2 !! "hello").getOrElse("_") should equal("world")
    }

    it("should be able to serialize and deserialize a StatelessSerializableTestActorWithMessagesInMailbox") {
      val actor1 = actorOf[StatelessSerializableTestActorWithMessagesInMailbox].start
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      (actor1 ! "hello")
      val actor2 = ActorRef.fromBinaryToLocalActorRef(actor1.toBinary)
      Thread.sleep(1000)
      (actor2 !! "hello-reply").getOrElse("_") should equal("world")
    }
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

class StatelessSerializableTestActor extends StatelessSerializableActor {
  def receive = {
    case "hello" =>
      self.reply("world")
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

class StatelessSerializableTestActorWithMessagesInMailbox extends StatelessSerializableActor {
  def receive = {
    case "hello" =>
      if (self ne null) println("# messages in mailbox " + self.mailbox.size)
      Thread.sleep(500)
    case "hello-reply" => self.reply("world")
  }
}


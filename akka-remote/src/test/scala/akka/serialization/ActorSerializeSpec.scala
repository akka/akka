package akka.serialization

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import com.google.protobuf.Message

import akka.serialization.ActorSerialization._
import akka.actor._
import Actor._
import SerializeSpec._

case class MyMessage(id: Long, name: String, status: Boolean)

@RunWith(classOf[JUnitRunner])
class ActorSerializeSpec extends Spec with ShouldMatchers with BeforeAndAfterAll {

  describe("Serializable actor") {
    it("should be able to serialize and de-serialize a stateful actor with a given serializer") {

      val actor1 = new LocalActorRef(Props[MyJavaSerializableActor], newUuid.toString, systemService = true)
      (actor1 ? "hello").get should equal("world 1")
      (actor1 ? "hello").get should equal("world 2")

      val bytes = toBinary(actor1)
      val actor2 = fromBinary(bytes).asInstanceOf[LocalActorRef]
      (actor2 ? "hello").get should equal("world 3")

      actor2.receiveTimeout should equal(Some(1000))
      actor1.stop()
      actor2.stop()
    }

    it("should be able to serialize and deserialize a MyStatelessActorWithMessagesInMailbox") {

      val actor1 = new LocalActorRef(Props[MyStatelessActorWithMessagesInMailbox], newUuid.toString, systemService = true)
      for (i ← 1 to 10) actor1 ! "hello"

      actor1.getDispatcher.mailboxSize(actor1.underlying) should be > (0)
      val actor2 = fromBinary(toBinary(actor1)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor2.getDispatcher.mailboxSize(actor1.underlying) should be > (0)
      (actor2 ? "hello-reply").get should equal("world")

      val actor3 = fromBinary(toBinary(actor1, false)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor3.getDispatcher.mailboxSize(actor1.underlying) should equal(0)
      (actor3 ? "hello-reply").get should equal("world")
    }

    it("should be able to serialize and deserialize a PersonActorWithMessagesInMailbox") {

      val p1 = Person("debasish ghosh", 25, SerializeSpec.Address("120", "Monroe Street", "Santa Clara", "95050"))
      val actor1 = new LocalActorRef(Props[PersonActorWithMessagesInMailbox], newUuid.toString, systemService = true)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      (actor1 ! p1)
      actor1.getDispatcher.mailboxSize(actor1.underlying) should be > (0)
      val actor2 = fromBinary(toBinary(actor1)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor2.getDispatcher.mailboxSize(actor1.underlying) should be > (0)
      (actor2 ? "hello-reply").get should equal("hello")

      val actor3 = fromBinary(toBinary(actor1, false)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor3.getDispatcher.mailboxSize(actor1.underlying) should equal(0)
      (actor3 ? "hello-reply").get should equal("hello")
    }
  }

  describe("serialize protobuf") {
    it("should serialize") {
      val msg = MyMessage(123, "debasish ghosh", true)
      import akka.serialization.Serialization._
      val b = serialize(ProtobufProtocol.MyMessage.newBuilder.setId(msg.id).setName(msg.name).setStatus(msg.status).build) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      val in = deserialize(b, classOf[ProtobufProtocol.MyMessage], None) match {
        case Left(exception) ⇒ fail(exception)
        case Right(i)        ⇒ i
      }
      val m = in.asInstanceOf[ProtobufProtocol.MyMessage]
      MyMessage(m.getId, m.getName, m.getStatus) should equal(msg)
    }
  }

  describe("serialize actor that accepts protobuf message") {
    it("should serialize") {

      val actor1 = new LocalActorRef(Props[MyActorWithProtobufMessagesInMailbox], newUuid.toString, systemService = true)
      val msg = MyMessage(123, "debasish ghosh", true)
      val b = ProtobufProtocol.MyMessage.newBuilder.setId(msg.id).setName(msg.name).setStatus(msg.status).build
      for (i ← 1 to 10) actor1 ! b
      actor1.getDispatcher.mailboxSize(actor1.underlying) should be > (0)
      val actor2 = fromBinary(toBinary(actor1)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor2.getDispatcher.mailboxSize(actor1.underlying) should be > (0)
      (actor2 ? "hello-reply").get should equal("world")

      val actor3 = fromBinary(toBinary(actor1, false)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor3.getDispatcher.mailboxSize(actor1.underlying) should equal(0)
      (actor3 ? "hello-reply").get should equal("world")
    }
  }
}

class MyJavaSerializableActor extends Actor with scala.Serializable {
  var count = 0
  self.receiveTimeout = Some(1000)

  def receive = {
    case "hello" ⇒
      count = count + 1
      self.reply("world " + count)
  }
}

class MyStatelessActorWithMessagesInMailbox extends Actor with scala.Serializable {
  def receive = {
    case "hello" ⇒
      Thread.sleep(500)
    case "hello-reply" ⇒ self.reply("world")
  }
}

class MyActorWithProtobufMessagesInMailbox extends Actor with scala.Serializable {
  def receive = {
    case m: Message ⇒
      Thread.sleep(500)
    case "hello-reply" ⇒ self.reply("world")
  }
}

class PersonActorWithMessagesInMailbox extends Actor with scala.Serializable {
  def receive = {
    case p: Person ⇒
      Thread.sleep(500)
    case "hello-reply" ⇒ self.reply("hello")
  }
}

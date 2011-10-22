package akka.serialization

import org.scalatest.BeforeAndAfterAll
import com.google.protobuf.Message
import akka.actor._
import akka.remote._
import akka.testkit.AkkaSpec
import akka.serialization.SerializeSpec.Person

case class MyMessage(id: Long, name: String, status: Boolean)

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSerializeSpec extends AkkaSpec with BeforeAndAfterAll {

  lazy val remote: Remote = {
    app.provider match {
      case r: RemoteActorRefProvider ⇒ r.remote
      case _                         ⇒ throw new Exception("Remoting is not enabled")
    }
  }

  lazy val serialization = new ActorSerialization(app, remote.server)

  "Serializable actor" must {
    "must be able to serialize and de-serialize a stateful actor with a given serializer" ignore {

      val actor1 = new LocalActorRef(app, Props[MyJavaSerializableActor], app.guardian, Props.randomAddress, systemService = true)

      (actor1 ? "hello").get must equal("world 1")
      (actor1 ? "hello").get must equal("world 2")

      val bytes = serialization.toBinary(actor1)
      val actor2 = serialization.fromBinary(bytes).asInstanceOf[LocalActorRef]
      (actor2 ? "hello").get must equal("world 3")

      actor2.underlying.receiveTimeout must equal(Some(1000))
      actor1.stop()
      actor2.stop()
    }

    "must be able to serialize and deserialize a MyStatelessActorWithMessagesInMailbox" ignore {

      val actor1 = new LocalActorRef(app, Props[MyStatelessActorWithMessagesInMailbox], app.guardian, Props.randomAddress, systemService = true)
      for (i ← 1 to 10) actor1 ! "hello"

      actor1.underlying.dispatcher.mailboxSize(actor1.underlying) must be > (0)
      val actor2 = serialization.fromBinary(serialization.toBinary(actor1)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor2.underlying.dispatcher.mailboxSize(actor1.underlying) must be > (0)
      (actor2 ? "hello-reply").get must equal("world")

      val actor3 = serialization.fromBinary(serialization.toBinary(actor1, false)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor3.underlying.dispatcher.mailboxSize(actor1.underlying) must equal(0)
      (actor3 ? "hello-reply").get must equal("world")
    }

    "must be able to serialize and deserialize a PersonActorWithMessagesInMailbox" ignore {

      val p1 = Person("debasish ghosh", 25, SerializeSpec.Address("120", "Monroe Street", "Santa Clara", "95050"))
      val actor1 = new LocalActorRef(app, Props[PersonActorWithMessagesInMailbox], app.guardian, Props.randomAddress, systemService = true)
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
      actor1.underlying.dispatcher.mailboxSize(actor1.underlying) must be > (0)
      val actor2 = serialization.fromBinary(serialization.toBinary(actor1)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor2.underlying.dispatcher.mailboxSize(actor1.underlying) must be > (0)
      (actor2 ? "hello-reply").get must equal("hello")

      val actor3 = serialization.fromBinary(serialization.toBinary(actor1, false)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor3.underlying.dispatcher.mailboxSize(actor1.underlying) must equal(0)
      (actor3 ? "hello-reply").get must equal("hello")
    }
  }

  "serialize protobuf" must {
    "must serialize" ignore {
      val msg = MyMessage(123, "debasish ghosh", true)

      val ser = new Serialization(app)

      val b = ser.serialize(ProtobufProtocol.MyMessage.newBuilder.setId(msg.id).setName(msg.name).setStatus(msg.status).build) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      val in = ser.deserialize(b, classOf[ProtobufProtocol.MyMessage], None) match {
        case Left(exception) ⇒ fail(exception)
        case Right(i)        ⇒ i
      }
      val m = in.asInstanceOf[ProtobufProtocol.MyMessage]
      MyMessage(m.getId, m.getName, m.getStatus) must equal(msg)
    }
  }

  "serialize actor that accepts protobuf message" ignore {
    "must serialize" ignore {

      val actor1 = new LocalActorRef(app, Props[MyActorWithProtobufMessagesInMailbox], app.guardian, Props.randomAddress, systemService = true)
      val msg = MyMessage(123, "debasish ghosh", true)
      val b = ProtobufProtocol.MyMessage.newBuilder.setId(msg.id).setName(msg.name).setStatus(msg.status).build
      for (i ← 1 to 10) actor1 ! b
      actor1.underlying.dispatcher.mailboxSize(actor1.underlying) must be > (0)
      val actor2 = serialization.fromBinary(serialization.toBinary(actor1)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor2.underlying.dispatcher.mailboxSize(actor1.underlying) must be > (0)
      (actor2 ? "hello-reply").get must equal("world")

      val actor3 = serialization.fromBinary(serialization.toBinary(actor1, false)).asInstanceOf[LocalActorRef]
      Thread.sleep(1000)
      actor3.underlying.dispatcher.mailboxSize(actor1.underlying) must equal(0)
      (actor3 ? "hello-reply").get must equal("world")
    }
  }
}

class MyJavaSerializableActor extends Actor with scala.Serializable {
  var count = 0
  receiveTimeout = Some(1000)

  def receive = {
    case "hello" ⇒
      count = count + 1
      sender ! "world " + count
  }
}

class MyStatelessActorWithMessagesInMailbox extends Actor with scala.Serializable {
  def receive = {
    case "hello" ⇒
      Thread.sleep(500)
    case "hello-reply" ⇒ sender ! "world"
  }
}

class MyActorWithProtobufMessagesInMailbox extends Actor with scala.Serializable {
  def receive = {
    case m: Message ⇒
      Thread.sleep(500)
    case "hello-reply" ⇒ sender ! "world"
  }
}

class PersonActorWithMessagesInMailbox extends Actor with scala.Serializable {
  def receive = {
    case p: Person ⇒
      Thread.sleep(500)
    case "hello-reply" ⇒ sender ! "hello"
  }
}

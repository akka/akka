/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import akka.actor._
import java.io._
import akka.dispatch.Await
import akka.util.Timeout
import akka.util.duration._
import scala.reflect.BeanInfo
import com.google.protobuf.Message
import akka.pattern.ask

object SerializeSpec {

  val serializationConf = ConfigFactory.parseString("""
    akka {
      actor {
        serializers {
          java = "akka.serialization.JavaSerializer"
          test = "akka.serialization.TestSerializer"
        }

        serialization-bindings {
          java = ["akka.serialization.SerializeSpec$Person", "akka.serialization.SerializeSpec$Address", "akka.serialization.MyJavaSerializableActor", "akka.serialization.MyStatelessActorWithMessagesInMailbox", "akka.serialization.MyActorWithProtobufMessagesInMailbox"]
          test = ["akka.serialization.TestSerializble", "akka.serialization.SerializeSpec$PlainMessage"]
        }
      }
    }
  """)

  @BeanInfo
  case class Address(no: String, street: String, city: String, zip: String) { def this() = this("", "", "", "") }
  @BeanInfo
  case class Person(name: String, age: Int, address: Address) { def this() = this("", 0, null) }

  case class Record(id: Int, person: Person)

  class SimpleMessage(s: String) extends TestSerializble

  class ExtendedSimpleMessage(s: String, i: Int) extends SimpleMessage(s)

  trait AnotherInterface extends TestSerializble

  class AnotherMessage extends AnotherInterface

  class ExtendedAnotherMessage extends AnotherMessage

  class PlainMessage

  class ExtendedPlainMessage extends PlainMessage

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SerializeSpec extends AkkaSpec(SerializeSpec.serializationConf) {
  import SerializeSpec._

  val ser = SerializationExtension(system)
  import ser._

  val addr = Address("120", "Monroe Street", "Santa Clara", "95050")
  val person = Person("debasish ghosh", 25, Address("120", "Monroe Street", "Santa Clara", "95050"))

  "Serialization" must {

    "have correct bindings" in {
      ser.bindings(addr.getClass.getName) must be("java")
      ser.bindings(classOf[PlainMessage].getName) must be("test")
    }

    "serialize Address" in {
      val b = serialize(addr) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      deserialize(b.asInstanceOf[Array[Byte]], classOf[Address], None) match {
        case Left(exception) ⇒ fail(exception)
        case Right(add)      ⇒ assert(add === addr)
      }
    }

    "serialize Person" in {

      val b = serialize(person) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      deserialize(b.asInstanceOf[Array[Byte]], classOf[Person], None) match {
        case Left(exception) ⇒ fail(exception)
        case Right(p)        ⇒ assert(p === person)
      }
    }

    "serialize record with default serializer" in {

      val r = Record(100, person)
      val b = serialize(r) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      deserialize(b.asInstanceOf[Array[Byte]], classOf[Record], None) match {
        case Left(exception) ⇒ fail(exception)
        case Right(p)        ⇒ assert(p === r)
      }
    }

    "not serialize ActorCell" in {
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case o: ObjectOutputStream ⇒
            try {
              o.writeObject(this)
            } catch {
              case _: NotSerializableException ⇒ testActor ! "pass"
            }
        }
      }))
      a ! new ObjectOutputStream(new ByteArrayOutputStream())
      expectMsg("pass")
      system.stop(a)
    }

    "serialize DeadLetterActorRef" in {
      val outbuf = new ByteArrayOutputStream()
      val out = new ObjectOutputStream(outbuf)
      val a = ActorSystem("SerializeDeadLeterActorRef", AkkaSpec.testConf)
      try {
        out.writeObject(a.deadLetters)
        out.flush()
        out.close()

        val in = new ObjectInputStream(new ByteArrayInputStream(outbuf.toByteArray))
        Serialization.currentSystem.withValue(a.asInstanceOf[ActorSystemImpl]) {
          val deadLetters = in.readObject().asInstanceOf[DeadLetterActorRef]
          (deadLetters eq a.deadLetters) must be(true)
        }
      } finally {
        a.shutdown()
      }
    }

    "resove serializer by direct interface" in {
      val msg = new SimpleMessage("foo")
      ser.serializerFor(msg.getClass).getClass must be(classOf[TestSerializer])
    }

    "resove serializer by interface implemented by super class" in {
      val msg = new ExtendedSimpleMessage("foo", 17)
      ser.serializerFor(msg.getClass).getClass must be(classOf[TestSerializer])
    }

    "resove serializer by indirect interface" in {
      val msg = new AnotherMessage
      ser.serializerFor(msg.getClass).getClass must be(classOf[TestSerializer])
    }

    "resove serializer by indirect interface implemented by super class" in {
      val msg = new ExtendedAnotherMessage
      ser.serializerFor(msg.getClass).getClass must be(classOf[TestSerializer])
    }

    "resove serializer for message with binding" in {
      val msg = new PlainMessage
      ser.serializerFor(msg.getClass).getClass must be(classOf[TestSerializer])
    }

    "resove serializer for message extending class with with binding" in {
      val msg = new ExtendedPlainMessage
      ser.serializerFor(msg.getClass).getClass must be(classOf[TestSerializer])
    }

  }
}

object VerifySerializabilitySpec {
  val conf = ConfigFactory.parseString("""
    akka {
      actor {
        serialize-messages = on

        serialize-creators = on

        serializers {
          java = "akka.serialization.JavaSerializer"
          default = "akka.serialization.JavaSerializer"
        }

        serialization-bindings {
          java = ["akka.serialization.SerializeSpec$Address", "akka.serialization.MyJavaSerializableActor", "akka.serialization.MyStatelessActorWithMessagesInMailbox", "akka.serialization.MyActorWithProtobufMessagesInMailbox"]
        }
      }
    }
  """)

  class FooActor extends Actor {
    def receive = {
      case s: String ⇒ sender ! s
    }
  }

  class FooUntypedActor extends UntypedActor {
    def onReceive(message: Any) {}
  }

  class NonSerializableActor(system: ActorSystem) extends Actor {
    def receive = {
      case s: String ⇒ sender ! s
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class VerifySerializabilitySpec extends AkkaSpec(VerifySerializabilitySpec.conf) {
  import VerifySerializabilitySpec._
  implicit val timeout = Timeout(5 seconds)

  "verify config" in {
    system.settings.SerializeAllCreators must be(true)
    system.settings.SerializeAllMessages must be(true)
  }

  "verify creators" in {
    val a = system.actorOf(Props[FooActor])
    system stop a

    val b = system.actorOf(Props(new FooActor))
    system stop b

    val c = system.actorOf(Props().withCreator(new UntypedActorFactory {
      def create() = new FooUntypedActor
    }))
    system stop c

    intercept[java.io.NotSerializableException] {
      val d = system.actorOf(Props(new NonSerializableActor(system)))
    }

  }

  "verify messages" in {
    val a = system.actorOf(Props[FooActor])
    Await.result(a ? "pigdog", timeout.duration) must be("pigdog")

    intercept[NotSerializableException] {
      Await.result(a ? new AnyRef, timeout.duration)
    }
    system stop a
  }
}

trait TestSerializble

class TestSerializer extends Serializer {
  def includeManifest: Boolean = false

  def identifier = 9999

  def toBinary(o: AnyRef): Array[Byte] = {
    Array.empty[Byte]
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]] = None,
                 classLoader: Option[ClassLoader] = None): AnyRef = {
    null
  }
}

/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import language.postfixOps

import akka.testkit.{ AkkaSpec, EventFilter }
import akka.actor._
import java.io._
import akka.dispatch.Await
import akka.util.Timeout
import scala.concurrent.util.duration._
import scala.reflect.BeanInfo
import com.google.protobuf.Message
import akka.pattern.ask

object SerializeSpec {

  val config = """
    akka {
      actor {
        serializers {
          test = "akka.serialization.TestSerializer"
        }

        serialization-bindings {
          "akka.serialization.SerializeSpec$Person" = java
          "akka.serialization.SerializeSpec$Address" = java
          "akka.serialization.TestSerializble" = test
          "akka.serialization.SerializeSpec$PlainMessage" = test
          "akka.serialization.SerializeSpec$A" = java
          "akka.serialization.SerializeSpec$B" = test
          "akka.serialization.SerializeSpec$D" = test
        }
      }
    }
  """

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

  class Both(s: String) extends SimpleMessage(s) with Serializable

  trait A
  trait B
  class C extends B with A
  class D extends A
  class E extends D

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SerializeSpec extends AkkaSpec(SerializeSpec.config) {
  import SerializeSpec._

  val ser = SerializationExtension(system)
  import ser._

  val addr = Address("120", "Monroe Street", "Santa Clara", "95050")
  val person = Person("debasish ghosh", 25, Address("120", "Monroe Street", "Santa Clara", "95050"))

  "Serialization" must {

    "have correct bindings" in {
      ser.bindings.collectFirst { case (c, s) if c == addr.getClass ⇒ s.getClass } must be(Some(classOf[JavaSerializer]))
      ser.bindings.collectFirst { case (c, s) if c == classOf[PlainMessage] ⇒ s.getClass } must be(Some(classOf[TestSerializer]))
    }

    "serialize Address" in {
      val b = serialize(addr) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      deserialize(b.asInstanceOf[Array[Byte]], classOf[Address]) match {
        case Left(exception) ⇒ fail(exception)
        case Right(add)      ⇒ assert(add === addr)
      }
    }

    "serialize Person" in {

      val b = serialize(person) match {
        case Left(exception) ⇒ fail(exception)
        case Right(bytes)    ⇒ bytes
      }
      deserialize(b.asInstanceOf[Array[Byte]], classOf[Person]) match {
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
      deserialize(b.asInstanceOf[Array[Byte]], classOf[Record]) match {
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
        JavaSerializer.currentSystem.withValue(a.asInstanceOf[ActorSystemImpl]) {
          val deadLetters = in.readObject().asInstanceOf[DeadLetterActorRef]
          (deadLetters eq a.deadLetters) must be(true)
        }
      } finally {
        a.shutdown()
      }
    }

    "resolve serializer by direct interface" in {
      ser.serializerFor(classOf[SimpleMessage]).getClass must be(classOf[TestSerializer])
    }

    "resolve serializer by interface implemented by super class" in {
      ser.serializerFor(classOf[ExtendedSimpleMessage]).getClass must be(classOf[TestSerializer])
    }

    "resolve serializer by indirect interface" in {
      ser.serializerFor(classOf[AnotherMessage]).getClass must be(classOf[TestSerializer])
    }

    "resolve serializer by indirect interface implemented by super class" in {
      ser.serializerFor(classOf[ExtendedAnotherMessage]).getClass must be(classOf[TestSerializer])
    }

    "resolve serializer for message with binding" in {
      ser.serializerFor(classOf[PlainMessage]).getClass must be(classOf[TestSerializer])
    }

    "resolve serializer for message extending class with with binding" in {
      ser.serializerFor(classOf[ExtendedPlainMessage]).getClass must be(classOf[TestSerializer])
    }

    "give warning for message with several bindings" in {
      EventFilter.warning(start = "Multiple serializers found", occurrences = 1) intercept {
        ser.serializerFor(classOf[Both]).getClass must be(classOf[TestSerializer])
      }
    }

    "resolve serializer in the order of the bindings" in {
      ser.serializerFor(classOf[A]).getClass must be(classOf[JavaSerializer])
      ser.serializerFor(classOf[B]).getClass must be(classOf[TestSerializer])
      EventFilter.warning(start = "Multiple serializers found", occurrences = 1) intercept {
        ser.serializerFor(classOf[C]).getClass must be(classOf[JavaSerializer])
      }
    }

    "resolve serializer in the order of most specific binding first" in {
      ser.serializerFor(classOf[A]).getClass must be(classOf[JavaSerializer])
      ser.serializerFor(classOf[D]).getClass must be(classOf[TestSerializer])
      ser.serializerFor(classOf[E]).getClass must be(classOf[TestSerializer])
    }

    "throw java.io.NotSerializableException when no binding" in {
      intercept[java.io.NotSerializableException] {
        ser.serializerFor(classOf[Actor])
      }
    }

  }
}

object VerifySerializabilitySpec {
  val conf = """
    akka {
      actor {
        serialize-messages = on
        serialize-creators = on
      }
    }
  """

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

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = null
}

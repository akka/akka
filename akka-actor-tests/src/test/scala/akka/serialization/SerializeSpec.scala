/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import language.postfixOps

import akka.testkit.{ AkkaSpec, EventFilter }
import akka.actor._
import akka.dispatch._
import java.io._
import scala.concurrent.Await
import akka.util.Timeout
import scala.concurrent.duration._
import scala.reflect.BeanInfo
import com.google.protobuf.Message
import com.typesafe.config._
import akka.pattern.ask
import org.apache.commons.codec.binary.Hex.{ encodeHex, decodeHex }

object SerializationTests {

  val serializeConf = """
    akka {
      actor {
        serializers {
          test = "akka.serialization.TestSerializer"
        }

        serialization-bindings {
          "akka.serialization.SerializationTests$Person" = java
          "akka.serialization.SerializationTests$Address" = java
          "akka.serialization.TestSerializable" = test
          "akka.serialization.SerializationTests$PlainMessage" = test
          "akka.serialization.SerializationTests$A" = java
          "akka.serialization.SerializationTests$B" = test
          "akka.serialization.SerializationTests$D" = test
        }
      }
    }
  """

  @BeanInfo
  case class Address(no: String, street: String, city: String, zip: String) { def this() = this("", "", "", "") }
  @BeanInfo
  case class Person(name: String, age: Int, address: Address) { def this() = this("", 0, null) }

  case class Record(id: Int, person: Person)

  class SimpleMessage(s: String) extends TestSerializable

  class ExtendedSimpleMessage(s: String, i: Int) extends SimpleMessage(s)

  trait AnotherInterface extends TestSerializable

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

  val verifySerializabilityConf = """
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

  def mostlyReferenceSystem: ActorSystem = {
    val referenceConf = ConfigFactory.defaultReference()
    val mostlyReferenceConf = AkkaSpec.testConf.withFallback(referenceConf)
    ActorSystem("SerializationSystem", mostlyReferenceConf)
  }

  val systemMessageMultiSerializerConf = """
    akka {
      actor {
        serializers {
          test = "akka.serialization.TestSerializer"
        }

        serialization-bindings {
          "akka.dispatch.SystemMessage" = test
        }
      }
    }
  """

  val systemMessageClasses = List[Class[_]](
    classOf[Create],
    classOf[Recreate],
    classOf[Suspend],
    classOf[Resume],
    classOf[Terminate],
    classOf[Supervise],
    classOf[ChildTerminated],
    classOf[Watch],
    classOf[Unwatch],
    NoMessage.getClass)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SerializeSpec extends AkkaSpec(SerializationTests.serializeConf) {
  import SerializationTests._

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
      assert(deserialize(serialize(addr).get, classOf[Address]).get === addr)
    }

    "serialize Person" in {
      assert(deserialize(serialize(person).get, classOf[Person]).get === person)
    }

    "serialize record with default serializer" in {
      val r = Record(100, person)
      assert(deserialize(serialize(r).get, classOf[Record]).get === r)
    }

    "not serialize ActorCell" in {
      val a = system.actorOf(Props(new Actor {
        def receive = {
          case o: ObjectOutputStream ⇒
            try o.writeObject(this) catch { case _: NotSerializableException ⇒ testActor ! "pass" }
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
        ser.serializerFor(classOf[Both]).getClass must (be(classOf[TestSerializer]) or be(classOf[JavaSerializer]))
      }
    }

    "resolve serializer in the order of the bindings" in {
      ser.serializerFor(classOf[A]).getClass must be(classOf[JavaSerializer])
      ser.serializerFor(classOf[B]).getClass must be(classOf[TestSerializer])
      EventFilter.warning(start = "Multiple serializers found", occurrences = 1) intercept {
        ser.serializerFor(classOf[C]).getClass must (be(classOf[TestSerializer]) or be(classOf[JavaSerializer]))
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

    "use ByteArraySerializer for byte arrays" in {
      val byteSerializer = ser.serializerFor(classOf[Array[Byte]])
      byteSerializer.getClass must be theSameInstanceAs classOf[ByteArraySerializer]

      for (a ← Seq("foo".getBytes("UTF-8"), null: Array[Byte], Array[Byte]()))
        byteSerializer.fromBinary(byteSerializer.toBinary(a)) must be theSameInstanceAs a

      intercept[IllegalArgumentException] {
        byteSerializer.toBinary("pigdog")
      }.getMessage must be === "ByteArraySerializer only serializes byte arrays, not [pigdog]"
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class VerifySerializabilitySpec extends AkkaSpec(SerializationTests.verifySerializabilityConf) {
  import SerializationTests._
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

    val c = system.actorOf(Props.empty.withCreator(new UntypedActorFactory {
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

    EventFilter[NotSerializableException](occurrences = 1) intercept {
      a ! (new AnyRef)
    }
    system stop a
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ReferenceSerializationSpec extends AkkaSpec(SerializationTests.mostlyReferenceSystem) {
  import SerializationTests._

  val ser = SerializationExtension(system)
  def serializerMustBe(toSerialize: Class[_], expectedSerializer: Class[_]) =
    ser.serializerFor(toSerialize).getClass must be(expectedSerializer)

  "Serialization settings from reference.conf" must {

    "declare Serializable classes to be use JavaSerializer" in {
      serializerMustBe(classOf[Serializable], classOf[JavaSerializer])
      serializerMustBe(classOf[String], classOf[JavaSerializer])
      for (smc ← systemMessageClasses) {
        serializerMustBe(smc, classOf[JavaSerializer])
      }
    }

    "declare Array[Byte] to use ByteArraySerializer" in {
      serializerMustBe(classOf[Array[Byte]], classOf[ByteArraySerializer])
    }

    "not support serialization for other classes" in {
      intercept[NotSerializableException] { ser.serializerFor(classOf[Object]) }
    }

  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class OverriddenSystemMessageSerializationSpec extends AkkaSpec(SerializationTests.systemMessageMultiSerializerConf) {
  import SerializationTests._

  val ser = SerializationExtension(system)

  "Overridden SystemMessage serialization" must {

    "resolve to a single serializer" in {
      EventFilter.warning(start = "Multiple serializers found", occurrences = 0) intercept {
        for (smc ← systemMessageClasses) {
          ser.serializerFor(smc).getClass must be(classOf[TestSerializer])
        }
      }
    }

  }
}

trait TestSerializable

class TestSerializer extends Serializer {
  def includeManifest: Boolean = false

  def identifier = 9999

  def toBinary(o: AnyRef): Array[Byte] = {
    Array.empty[Byte]
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = null
}

@SerialVersionUID(1)
case class FakeThrowable(msg: String) extends Throwable(msg) with Serializable {
  override def fillInStackTrace = null
}

@SerialVersionUID(1)
case class FakeActorRef(name: String) extends InternalActorRef with ActorRefScope {
  override def path = RootActorPath(Address("proto", "SomeSystem"), name)
  override def forward(message: Any)(implicit context: ActorContext) = ???
  override def isTerminated = ???
  override def start() = ???
  override def resume(causedByFailure: Throwable) = ???
  override def suspend() = ???
  override def restart(cause: Throwable) = ???
  override def stop() = ???
  override def sendSystemMessage(message: SystemMessage) = ???
  override def provider = ???
  override def getParent = ???
  override def getChild(name: Iterator[String]) = ???
  override def isLocal = ???
  override def !(message: Any)(implicit sender: ActorRef = Actor.noSender) = ???
}
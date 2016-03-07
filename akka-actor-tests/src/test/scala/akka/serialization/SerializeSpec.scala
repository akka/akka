/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.serialization

import language.postfixOps

import akka.testkit.{ AkkaSpec, EventFilter }
import akka.actor._
import akka.dispatch.sysmsg._
import java.io._
import scala.concurrent.Await
import akka.util.Timeout
import scala.concurrent.duration._
import scala.beans.BeanInfo
import com.typesafe.config._
import akka.pattern.ask
import org.apache.commons.codec.binary.Hex.encodeHex

object SerializationTests {

  val serializeConf = s"""
    akka {
      actor {
        serialize-messages = off
        serializers {
          test = "akka.serialization.TestSerializer"
        }

        serialization-bindings {
          "akka.serialization.SerializationTests$$Person" = java
          "akka.serialization.SerializationTests$$Address" = java
          "akka.serialization.TestSerializable" = test
          "akka.serialization.SerializationTests$$PlainMessage" = test
          "akka.serialization.SerializationTests$$A" = java
          "akka.serialization.SerializationTests$$B" = test
          "akka.serialization.SerializationTests$$D" = test
        }
      }
    }
  """

  @BeanInfo
  final case class Address(no: String, street: String, city: String, zip: String) { def this() = this("", "", "", "") }
  @BeanInfo
  final case class Person(name: String, age: Int, address: Address) { def this() = this("", 0, null) }

  final case class Record(id: Int, person: Person)

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
      case s: String ⇒ sender() ! s
    }
  }

  class FooUntypedActor extends UntypedActor {
    def onReceive(message: Any) {}
  }

  class NonSerializableActor(system: ActorSystem) extends Actor {
    def receive = {
      case s: String ⇒ sender() ! s
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
          "akka.dispatch.sysmsg.SystemMessage" = test
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
    classOf[Watch],
    classOf[Unwatch],
    classOf[Failed],
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
      ser.bindings.collectFirst { case (c, s) if c == addr.getClass ⇒ s.getClass } should ===(Some(classOf[JavaSerializer]))
      ser.bindings.collectFirst { case (c, s) if c == classOf[PlainMessage] ⇒ s.getClass } should ===(Some(classOf[TestSerializer]))
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
          (deadLetters eq a.deadLetters) should ===(true)
        }
      } finally {
        shutdown(a)
      }
    }

    "resolve serializer by direct interface" in {
      ser.serializerFor(classOf[SimpleMessage]).getClass should ===(classOf[TestSerializer])
    }

    "resolve serializer by interface implemented by super class" in {
      ser.serializerFor(classOf[ExtendedSimpleMessage]).getClass should ===(classOf[TestSerializer])
    }

    "resolve serializer by indirect interface" in {
      ser.serializerFor(classOf[AnotherMessage]).getClass should ===(classOf[TestSerializer])
    }

    "resolve serializer by indirect interface implemented by super class" in {
      ser.serializerFor(classOf[ExtendedAnotherMessage]).getClass should ===(classOf[TestSerializer])
    }

    "resolve serializer for message with binding" in {
      ser.serializerFor(classOf[PlainMessage]).getClass should ===(classOf[TestSerializer])
    }

    "resolve serializer for message extending class with with binding" in {
      ser.serializerFor(classOf[ExtendedPlainMessage]).getClass should ===(classOf[TestSerializer])
    }

    "give warning for message with several bindings" in {
      EventFilter.warning(start = "Multiple serializers found", occurrences = 1) intercept {
        ser.serializerFor(classOf[Both]).getClass should (be(classOf[TestSerializer]) or be(classOf[JavaSerializer]))
      }
    }

    "resolve serializer in the order of the bindings" in {
      ser.serializerFor(classOf[A]).getClass should ===(classOf[JavaSerializer])
      ser.serializerFor(classOf[B]).getClass should ===(classOf[TestSerializer])
      EventFilter.warning(start = "Multiple serializers found", occurrences = 1) intercept {
        ser.serializerFor(classOf[C]).getClass should (be(classOf[TestSerializer]) or be(classOf[JavaSerializer]))
      }
    }

    "resolve serializer in the order of most specific binding first" in {
      ser.serializerFor(classOf[A]).getClass should ===(classOf[JavaSerializer])
      ser.serializerFor(classOf[D]).getClass should ===(classOf[TestSerializer])
      ser.serializerFor(classOf[E]).getClass should ===(classOf[TestSerializer])
    }

    "throw java.io.NotSerializableException when no binding" in {
      intercept[java.io.NotSerializableException] {
        ser.serializerFor(classOf[Actor])
      }
    }

    "use ByteArraySerializer for byte arrays" in {
      val byteSerializer = ser.serializerFor(classOf[Array[Byte]])
      byteSerializer.getClass should be theSameInstanceAs classOf[ByteArraySerializer]

      for (a ← Seq("foo".getBytes("UTF-8"), null: Array[Byte], Array[Byte]()))
        byteSerializer.fromBinary(byteSerializer.toBinary(a)) should be theSameInstanceAs a

      intercept[IllegalArgumentException] {
        byteSerializer.toBinary("pigdog")
      }.getMessage should ===("ByteArraySerializer only serializes byte arrays, not [pigdog]")
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class VerifySerializabilitySpec extends AkkaSpec(SerializationTests.verifySerializabilityConf) {
  import SerializationTests._
  implicit val timeout = Timeout(5 seconds)

  "verify config" in {
    system.settings.SerializeAllCreators should ===(true)
    system.settings.SerializeAllMessages should ===(true)
  }

  "verify creators" in {
    val a = system.actorOf(Props[FooActor])
    system stop a

    val b = system.actorOf(Props(new FooActor))
    system stop b

    intercept[IllegalArgumentException] {
      val d = system.actorOf(Props(new NonSerializableActor(system)))
    }

  }

  "verify messages" in {
    val a = system.actorOf(Props[FooActor])
    Await.result(a ? "pigdog", timeout.duration) should ===("pigdog")

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
    ser.serializerFor(toSerialize).getClass should ===(expectedSerializer)

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
class SerializationCompatibilitySpec extends AkkaSpec(SerializationTests.mostlyReferenceSystem) {

  val ser = SerializationExtension(system)

  "Cross-version serialization compatibility" must {
    def verify(obj: SystemMessage, asExpected: String): Unit =
      String.valueOf(ser.serialize(obj).map(encodeHex).get) should ===(asExpected)

    "be preserved for the Create SystemMessage" in {
      // Using null as the cause to avoid a large serialized message and JDK differences
      verify(Create(Some(null)),
        if (scala.util.Properties.versionNumberString.startsWith("2.10.")) {
          "aced00057372001b616b6b612e64697370617463682e7379736d73672e4372656174650000000000" +
            "0000010200014c00076661696c75726574000e4c7363616c612f4f7074696f6e3b78707372000a73" +
            "63616c612e536f6d65e2a09f87fc0836ae0200014c0001787400124c6a6176612f6c616e672f4f62" +
            "6a6563743b7872000c7363616c612e4f7074696f6ee36024a8328a45e9020000787070"
        } else {
          "aced00057372001b616b6b612e64697370617463682e7379736d73672e4372656174650000000000" +
            "0000010200014c00076661696c75726574000e4c7363616c612f4f7074696f6e3b78707372000a73" +
            "63616c612e536f6d651122f2695ea18b740200014c0001787400124c6a6176612f6c616e672f4f62" +
            "6a6563743b7872000c7363616c612e4f7074696f6efe6937fddb0e6674020000787070"
        })
    }
    "be preserved for the Recreate SystemMessage" in {
      verify(Recreate(null),
        "aced00057372001d616b6b612e64697370617463682e7379736d73672e5265637265617465000000" +
          "00000000010200014c000563617573657400154c6a6176612f6c616e672f5468726f7761626c653b" +
          "787070")
    }
    "be preserved for the Suspend SystemMessage" in {
      verify(Suspend(),
        "aced00057372001c616b6b612e64697370617463682e7379736d73672e53757370656e6400000000" +
          "000000010200007870")
    }
    "be preserved for the Resume SystemMessage" in {
      verify(Resume(null),
        "aced00057372001b616b6b612e64697370617463682e7379736d73672e526573756d650000000000" +
          "0000010200014c000f63617573656442794661696c7572657400154c6a6176612f6c616e672f5468" +
          "726f7761626c653b787070")
    }
    "be preserved for the Terminate SystemMessage" in {
      verify(Terminate(),
        "aced00057372001e616b6b612e64697370617463682e7379736d73672e5465726d696e6174650000" +
          "0000000000010200007870")
    }
    "be preserved for the Supervise SystemMessage" in {
      verify(Supervise(null, true),
        "aced00057372001e616b6b612e64697370617463682e7379736d73672e5375706572766973650000" +
          "0000000000010200025a00056173796e634c00056368696c647400154c616b6b612f6163746f722f" +
          "4163746f725265663b78700170")
    }
    "be preserved for the Watch SystemMessage" in {
      verify(Watch(null, null),
        "aced00057372001a616b6b612e64697370617463682e7379736d73672e5761746368000000000000" +
          "00010200024c00077761746368656574001d4c616b6b612f6163746f722f496e7465726e616c4163" +
          "746f725265663b4c00077761746368657271007e000178707070")
    }
    "be preserved for the Unwatch SystemMessage" in {
      verify(Unwatch(null, null),
        "aced00057372001c616b6b612e64697370617463682e7379736d73672e556e776174636800000000" +
          "000000010200024c0007776174636865657400154c616b6b612f6163746f722f4163746f72526566" +
          "3b4c00077761746368657271007e000178707070")
    }
    "be preserved for the NoMessage SystemMessage" in {
      verify(NoMessage,
        "aced00057372001f616b6b612e64697370617463682e7379736d73672e4e6f4d6573736167652400" +
          "000000000000010200007870")
    }
    "be preserved for the Failed SystemMessage" in {
      // Using null as the cause to avoid a large serialized message and JDK differences
      verify(Failed(null, cause = null, uid = 0),
        "aced00057372001b616b6b612e64697370617463682e7379736d73672e4661696c65640000000000" +
          "0000010200034900037569644c000563617573657400154c6a6176612f6c616e672f5468726f7761" +
          "626c653b4c00056368696c647400154c616b6b612f6163746f722f4163746f725265663b78700000" +
          "00007070")
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
          ser.serializerFor(smc).getClass should ===(classOf[TestSerializer])
        }
      }
    }

  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DefaultSerializationWarningSpec extends AkkaSpec(
  ConfigFactory.parseString("akka.actor.warn-about-java-serializer-usage = on")) {

  val ser = SerializationExtension(system)
  val messagePrefix = "Using the default Java serializer for class.*"

  "Using the default Java serializer" must {

    "log a warning when serializing classes outside of java.lang package" in {
      EventFilter.warning(message = messagePrefix) intercept {
        ser.serializerFor(classOf[java.math.BigDecimal])
      }
    }

    "not log warning when serializing classes from java.lang package" in {
      EventFilter.warning(message = messagePrefix, occurrences = 0) intercept {
        ser.serializerFor(classOf[java.lang.String])
      }
    }

  }

}

protected[akka] trait TestSerializable

protected[akka] class TestSerializer extends Serializer {
  def includeManifest: Boolean = false

  def identifier = 9999

  def toBinary(o: AnyRef): Array[Byte] = {
    Array.empty[Byte]
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = null
}

@SerialVersionUID(1)
protected[akka] final case class FakeThrowable(msg: String) extends Throwable(msg) with Serializable {
  override def fillInStackTrace = null
}

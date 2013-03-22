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
class SerializationCompatibilitySpec extends AkkaSpec(SerializationTests.mostlyReferenceSystem) {
  import SerializationTests._

  val ser = SerializationExtension(system)

  "Cross-version serialization compatibility" must {
    def verify(obj: Any, asExpected: String): Unit =
      String.valueOf(encodeHex(ser.serialize(obj, obj.getClass).get)) must be(asExpected)

    "be preserved for the Create SystemMessage" in {
      verify(Create(), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720014616b6b612e64697370617463682e437265617465000000000000000302000078707671007e0003")
    }
    "be preserved for the Recreate SystemMessage" in {
      verify(Recreate(null), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720016616b6b612e64697370617463682e52656372656174650987c65c8d378a800200014c000563617573657400154c6a6176612f6c616e672f5468726f7761626c653b7870707671007e0003")
    }
    "be preserved for the Suspend SystemMessage" in {
      verify(Suspend(), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720015616b6b612e64697370617463682e53757370656e6464e531d5d134b59902000078707671007e0003")
    }
    "be preserved for the Resume SystemMessage" in {
      verify(Resume(null), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720014616b6b612e64697370617463682e526573756d65dc5e646d445fcb010200014c000f63617573656442794661696c7572657400154c6a6176612f6c616e672f5468726f7761626c653b7870707671007e0003")
    }
    "be preserved for the Terminate SystemMessage" in {
      verify(Terminate(), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720017616b6b612e64697370617463682e5465726d696e61746509d66ca68318700f02000078707671007e0003")
    }
    "be preserved for the Supervise SystemMessage" in {
      verify(Supervise(FakeActorRef("child"), true), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720017616b6b612e64697370617463682e53757065727669736500000000000000030200025a00056173796e634c00056368696c647400154c616b6b612f6163746f722f4163746f725265663b7870017372001f616b6b612e73657269616c697a6174696f6e2e46616b654163746f7252656600000000000000010200014c00046e616d657400124c6a6176612f6c616e672f537472696e673b7872001b616b6b612e6163746f722e496e7465726e616c4163746f725265660d0aa2ca1e82097602000078720013616b6b612e6163746f722e4163746f72526566c3585dde655f469402000078707400056368696c647671007e0003")
    }
    "be preserved for the ChildTerminated SystemMessage" in {
      verify(ChildTerminated(FakeActorRef("child")), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e000178707372001d616b6b612e64697370617463682e4368696c645465726d696e617465644c84222437ed5db40200014c00056368696c647400154c616b6b612f6163746f722f4163746f725265663b78707372001f616b6b612e73657269616c697a6174696f6e2e46616b654163746f7252656600000000000000010200014c00046e616d657400124c6a6176612f6c616e672f537472696e673b7872001b616b6b612e6163746f722e496e7465726e616c4163746f725265660d0aa2ca1e82097602000078720013616b6b612e6163746f722e4163746f72526566c3585dde655f469402000078707400056368696c647671007e0003")
    }
    "be preserved for the Watch SystemMessage" in {
      verify(Watch(FakeActorRef("watchee"), FakeActorRef("watcher")), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720013616b6b612e64697370617463682e57617463682e1e65bc74394fc40200024c0007776174636865657400154c616b6b612f6163746f722f4163746f725265663b4c00077761746368657271007e000478707372001f616b6b612e73657269616c697a6174696f6e2e46616b654163746f7252656600000000000000010200014c00046e616d657400124c6a6176612f6c616e672f537472696e673b7872001b616b6b612e6163746f722e496e7465726e616c4163746f725265660d0aa2ca1e82097602000078720013616b6b612e6163746f722e4163746f72526566c3585dde655f46940200007870740007776174636865657371007e0006740007776174636865727671007e0003")
    }
    "be preserved for the Unwatch SystemMessage" in {
      verify(Unwatch(FakeActorRef("watchee"), FakeActorRef("watcher")), "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720015616b6b612e64697370617463682e556e776174636858501f7ee63dc2100200024c0007776174636865657400154c616b6b612f6163746f722f4163746f725265663b4c00077761746368657271007e000478707372001f616b6b612e73657269616c697a6174696f6e2e46616b654163746f7252656600000000000000010200014c00046e616d657400124c6a6176612f6c616e672f537472696e673b7872001b616b6b612e6163746f722e496e7465726e616c4163746f725265660d0aa2ca1e82097602000078720013616b6b612e6163746f722e4163746f72526566c3585dde655f46940200007870740007776174636865657371007e0006740007776174636865727671007e0003")
    }
    "be preserved for the NoMessage SystemMessage" in {
      verify(NoMessage, "aced00057372000c7363616c612e5475706c6532bc7daadf46211a990200024c00025f317400124c6a6176612f6c616e672f4f626a6563743b4c00025f3271007e0001787073720018616b6b612e64697370617463682e4e6f4d65737361676524b401a3610ccb70dd02000078707671007e0003")
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
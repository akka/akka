/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.serialization.Serialization._
import scala.reflect._
import akka.testkit.AkkaSpec
import akka.actor.{ ActorSystem, ActorSystemImpl }
import java.io.{ ObjectInputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectOutputStream }
import akka.actor.DeadLetterActorRef
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions

object SerializeSpec {

  val serializationConf = ConfigFactory.parseString("""
    akka {
      actor {
        serializers {
          java = "akka.serialization.JavaSerializer"
          proto = "akka.testing.ProtobufSerializer"
          sjson = "akka.testing.SJSONSerializer"
          default = "akka.serialization.JavaSerializer"
        }
    
        serialization-bindings {
          java = ["akka.serialization.SerializeSpec$Address", "akka.serialization.MyJavaSerializableActor", "akka.serialization.MyStatelessActorWithMessagesInMailbox", "akka.serialization.MyActorWithProtobufMessagesInMailbox"]
          sjson = ["akka.serialization.SerializeSpec$Person"]
          proto = ["com.google.protobuf.Message", "akka.actor.ProtobufProtocol$MyMessage"]
        }
      }
    }
  """, ConfigParseOptions.defaults)

  @BeanInfo
  case class Address(no: String, street: String, city: String, zip: String) { def this() = this("", "", "", "") }
  @BeanInfo
  case class Person(name: String, age: Int, address: Address) { def this() = this("", 0, null) }

  case class Record(id: Int, person: Person)
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
      ser.bindings(person.getClass.getName) must be("sjson")
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

    "serialize DeadLetterActorRef" in {
      val outbuf = new ByteArrayOutputStream()
      val out = new ObjectOutputStream(outbuf)
      val a = ActorSystem()
      out.writeObject(a.deadLetters)
      out.flush()
      out.close()

      val in = new ObjectInputStream(new ByteArrayInputStream(outbuf.toByteArray))
      Serialization.currentSystem.withValue(a.asInstanceOf[ActorSystemImpl]) {
        val deadLetters = in.readObject().asInstanceOf[DeadLetterActorRef]
        (deadLetters eq a.deadLetters) must be(true)
      }
    }
  }
}

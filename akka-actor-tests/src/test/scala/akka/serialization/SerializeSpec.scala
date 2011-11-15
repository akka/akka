/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import akka.serialization.Serialization._
import scala.reflect._
import akka.testkit.AkkaSpec
import akka.actor.ActorSystem
import java.io.{ ObjectInputStream, ByteArrayInputStream, ByteArrayOutputStream, ObjectOutputStream }
import akka.actor.DeadLetterActorRef

object SerializeSpec {
  @BeanInfo
  case class Address(no: String, street: String, city: String, zip: String) { def this() = this("", "", "", "") }
  @BeanInfo
  case class Person(name: String, age: Int, address: Address) { def this() = this("", 0, null) }

  case class Record(id: Int, person: Person)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SerializeSpec extends AkkaSpec {
  import SerializeSpec._

  import app.serialization._

  "Serialization" must {

    "serialize Address" in {
      val addr = Address("120", "Monroe Street", "Santa Clara", "95050")
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
      val person = Person("debasish ghosh", 25, Address("120", "Monroe Street", "Santa Clara", "95050"))
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
      val person = Person("debasish ghosh", 25, Address("120", "Monroe Street", "Santa Clara", "95050"))
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
      val a = new ActorSystem()
      out.writeObject(a.deadLetters)
      out.flush()
      out.close()

      val in = new ObjectInputStream(new ByteArrayInputStream(outbuf.toByteArray))
      Serialization.app.withValue(a) {
        val deadLetters = in.readObject().asInstanceOf[DeadLetterActorRef]
        (deadLetters eq a.deadLetters) must be(true)
      }
    }
  }
}

/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.serialization.Serialization._
import scala.reflect._

object SerializeSpec {
  @BeanInfo
  case class Address(no: String, street: String, city: String, zip: String) { def this() = this("", "", "", "") }
  @BeanInfo
  case class Person(name: String, age: Int, address: Address) { def this() = this("", 0, null) }

  case class Record(id: Int, person: Person)
}

class SerializeSpec extends JUnitSuite {
  import SerializeSpec._

  @Test
  def shouldSerializeAddress {
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

  @Test
  def shouldSerializePerson {
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

  @Test
  def shouldSerializeRecordWithDefaultSerializer {
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
}

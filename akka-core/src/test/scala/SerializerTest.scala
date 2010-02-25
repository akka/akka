package se.scalablesolutions.akka.serialization

import junit.framework.TestCase

import org.scalatest.junit.JUnitSuite
import org.junit.{Test, Before, After, Ignore}

import scala.reflect.BeanInfo
@BeanInfo 
case class Foo(foo: String) {
  def this() = this(null)
}

@BeanInfo
case class MyMessage(val id: String, val value: Tuple2[String, Int]) {
  private def this() = this(null, null)
}


class SerializerTest extends JUnitSuite {
  @Test @Ignore // TODO: resolve test failure
  def shouldSerializeString = {
    val f = Foo("debasish")
    val json = Serializer.ScalaJSON.out(f)
    assert(new String(json) == """{"foo":"debasish"}""")
    val fo = Serializer.ScalaJSON.in[Foo](new String(json)).asInstanceOf[Foo]
    assert(fo == f)
  }

  @Test @Ignore // TODO: resolve test failure
  def shouldSerializeTuple2 = {
    val message = MyMessage("id", ("hello", 34))
    val json = Serializer.ScalaJSON.out(message)
    assert(new String(json) == """{"id":"id","value":{"hello":34}}""")
    val f = Serializer.ScalaJSON.in[MyMessage](new String(json)).asInstanceOf[MyMessage]
    assert(f == message)
    val g = Serializer.ScalaJSON.in[MyMessage](json).asInstanceOf[MyMessage]
    assert(f == message)
  }
}

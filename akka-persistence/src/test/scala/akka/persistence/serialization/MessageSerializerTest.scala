package akka.persistence.serialization

import java.util.Base64

import akka.actor.ActorSystem
import akka.persistence.PersistentRepr
import akka.serialization.{ Serialization, SerializationExtension, SerializerWithStringManifest }
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ FlatSpec, Matchers, TryValues }

object MessageSerializerTest {
  val useIdentifier = ConfigFactory.parseString(
    """
      | akka.actor {
      |    serializers {
      |        foo = "akka.persistence.serialization.FooSerializer"
      |        bar = "akka.persistence.serialization.BarSerializer"
      |    }
      |
      |    serialization-bindings {
      |      "akka.persistence.serialization.Foo" = foo
      |      "akka.persistence.serialization.Bar" = bar
      |    }
      |}
      |akka.persistence.journal.message-serializer.deserialize-using-manifest = false
    """.stripMargin
  )

  val useManifest = ConfigFactory.parseString(
    """
      | akka.actor {
      |    serializers {
      |        foo = "akka.persistence.serialization.FooSerializer"
      |        bar = "akka.persistence.serialization.BarSerializer"
      |    }
      |
      |    serialization-bindings {
      |      "akka.persistence.serialization.Foo" = foo
      |      "akka.persistence.serialization.Bar" = bar
      |    }
      |}
      |akka.persistence.journal.message-serializer.deserialize-using-manifest = true
    """.stripMargin
  )

  // contains id=20 and manifest Foo classname
  val FooWithId20 =
    "CjMIFBILZm9vLWluLXJlcHIaImFra2EucGVyc2lzdGVuY2Uuc2VyaWFsaXphdGlvbi5Gb28QARoiYWtrYS5wZXJzaXN0ZW5jZS5zZXJpYWxpemF0aW9uLkZvbw=="

  // contains id=21 and manifest Foo classname
  val FooWithId21 =
    "CjMIFRILZm9vLWluLXJlcHIaImFra2EucGVyc2lzdGVuY2Uuc2VyaWFsaXphdGlvbi5Gb28QARoiYWtrYS5wZXJzaXN0ZW5jZS5zZXJpYWxpemF0aW9uLkZvbw=="

  implicit class StringOps(that: String) {
    def toByteArray: Array[Byte] = Base64.getDecoder.decode(that)
  }
}

class MessageSerializerTest extends FlatSpec with Matchers with TryValues with ScalaFutures {

  import MessageSerializerTest._

  def withSerializer(config: Config)(test: Serialization ⇒ Unit): Unit = {
    val system: ActorSystem = ActorSystem("test", config)
    try test(SerializationExtension(system)) finally system.terminate().futureValue
  }

  "MessageSerializer configured to use serializer identifier to Foo" should "decode payload with serializerId=20" in withSerializer(MessageSerializerTest.useIdentifier) { serialization ⇒
    val repr = serialization.deserialize(FooWithId20.toByteArray, classOf[PersistentRepr])
    repr.success.value.payload shouldBe Foo("foo-in-repr")
  }

  it should "decode payload with serializerId=21 should not be Foo but a Bar" in withSerializer(MessageSerializerTest.useIdentifier) { serialization ⇒
    val repr = serialization.deserialize(FooWithId21.toByteArray, classOf[PersistentRepr])
    repr.success.value.payload should not be Foo("foo-in-repr")
  }

  "MessageSerializer configured to use manifest to Foo" should "decode payload using manifest" in withSerializer(MessageSerializerTest.useManifest) { serialization ⇒
    val repr = serialization.deserialize(FooWithId20.toByteArray, classOf[PersistentRepr])
    repr.success.value.payload shouldBe Foo("foo-in-repr")
  }

  it should "decode payload using manifest to Foo" in withSerializer(MessageSerializerTest.useManifest) { serialization ⇒
    val repr = serialization.deserialize(FooWithId21.toByteArray, classOf[PersistentRepr])
    repr.success.value.payload shouldBe Foo("foo-in-repr")
  }
}

final case class Foo(str: String)

final case class Bar(str: String)

class FooSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 20

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case Foo(str) ⇒ str.getBytes
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    Foo(new String(bytes))
}

class BarSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 21

  override def manifest(o: AnyRef): String = o.getClass.getName

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case Bar(str) ⇒ str.getBytes
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef =
    Bar(new String(bytes))
}
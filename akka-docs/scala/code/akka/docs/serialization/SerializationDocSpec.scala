/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.serialization

import org.scalatest.matchers.MustMatchers
import akka.testkit._
//#imports
import akka.actor.ActorSystem
import akka.serialization._
import com.typesafe.config.ConfigFactory

//#imports

//#my-own-serializer
class MyOwnSerializer extends Serializer {

  // This is whether "fromBinary" requires a "clazz" or not
  def includeManifest: Boolean = false

  // Pick a unique identifier for your Serializer,
  // you've got a couple of billions to choose from,
  // 0 - 16 is reserved by Akka itself
  def identifier = 1234567

  // "toBinary" serializes the given object to an Array of Bytes
  def toBinary(obj: AnyRef): Array[Byte] = {
    // Put the code that serializes the object here
    //#...
    Array[Byte]()
    //#...
  }

  // "fromBinary" deserializes the given array,
  // using the type hint (if any, see "includeManifest" above)
  // into the optionally provided classLoader.
  def fromBinary(bytes: Array[Byte],
                 clazz: Option[Class[_]],
                 classLoader: Option[ClassLoader] = None): AnyRef = {
    // Put your code that deserializes here
    //#...
    null
    //#...
  }
}
//#my-own-serializer

trait MyOwnSerializable
case class Customer(name: String) extends MyOwnSerializable

class SerializationDocSpec extends AkkaSpec {
  "demonstrate configuration of serialize messages" in {
    //#serialize-messages-config
    val config = ConfigFactory.parseString("""
      akka {
        actor {
          serialize-messages = on
        }
      }
    """)
    //#serialize-messages-config
    val a = ActorSystem("system", config)
    a.settings.SerializeAllMessages must be(true)
    a.shutdown()
  }

  "demonstrate configuration of serialize creators" in {
    //#serialize-creators-config
    val config = ConfigFactory.parseString("""
      akka {
        actor {
          serialize-creators = on
        }
      }
    """)
    //#serialize-creators-config
    val a = ActorSystem("system", config)
    a.settings.SerializeAllCreators must be(true)
    a.shutdown()
  }

  "demonstrate configuration of serializers" in {
    //#serialize-serializers-config
    val config = ConfigFactory.parseString("""
      akka {
        actor {
          serializers {
            java = "akka.serialization.JavaSerializer"
            proto = "akka.serialization.ProtobufSerializer"
            myown = "akka.docs.serialization.MyOwnSerializer"
          }
        }
      }
    """)
    //#serialize-serializers-config
    val a = ActorSystem("system", config)
    a.shutdown()
  }

  "demonstrate configuration of serialization-bindings" in {
    //#serialization-bindings-config
    val config = ConfigFactory.parseString("""
      akka {
        actor {
          serializers {
            java = "akka.serialization.JavaSerializer"
            proto = "akka.serialization.ProtobufSerializer"
            myown = "akka.docs.serialization.MyOwnSerializer"
          }

          serialization-bindings {
            "java.lang.String" = java
            "akka.docs.serialization.Customer" = java
            "com.google.protobuf.Message" = proto
            "akka.docs.serialization.MyOwnSerializable" = myown
            "java.lang.Boolean" = myown
          }
        }
      }
    """)
    //#serialization-bindings-config
    val a = ActorSystem("system", config)
    SerializationExtension(a).serializerFor(classOf[String]).getClass must equal(classOf[JavaSerializer])
    SerializationExtension(a).serializerFor(classOf[Customer]).getClass must equal(classOf[JavaSerializer])
    SerializationExtension(a).serializerFor(classOf[java.lang.Boolean]).getClass must equal(classOf[MyOwnSerializer])
    a.shutdown()
  }

  "demonstrate the programmatic API" in {
    //#programmatic
    val system = ActorSystem("example")

    // Get the Serialization Extension
    val serialization = SerializationExtension(system)

    // Have something to serialize
    val original = "woohoo"

    // Find the Serializer for it
    val serializer = serialization.findSerializerFor(original)

    // Turn it into bytes
    val bytes = serializer.toBinary(original)

    // Turn it back into an object
    val back = serializer.fromBinary(bytes,
      manifest = None,
      classLoader = None)

    // Voilá!
    back must equal(original)

    //#programmatic
    system.shutdown()
  }
}

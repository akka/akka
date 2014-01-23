/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.serialization {

  import akka.testkit._
  //#imports
  import akka.actor.{ ActorRef, ActorSystem }
  import akka.serialization._
  import com.typesafe.config.ConfigFactory

  //#imports
  import akka.actor.ExtensionKey
  import akka.actor.ExtendedActorSystem
  import akka.actor.Extension
  import akka.actor.Address

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
                   clazz: Option[Class[_]]): AnyRef = {
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
      val config = ConfigFactory.parseString("""
      #//#serialize-messages-config
      akka {
        actor {
          serialize-messages = on
        }
      }
      #//#serialize-messages-config
      """)
      val a = ActorSystem("system", config)
      a.settings.SerializeAllMessages should be(true)
      shutdown(a)
    }

    "demonstrate configuration of serialize creators" in {
      val config = ConfigFactory.parseString("""
      #//#serialize-creators-config
      akka {
        actor {
          serialize-creators = on
        }
      }
      #//#serialize-creators-config
      """)
      val a = ActorSystem("system", config)
      a.settings.SerializeAllCreators should be(true)
      shutdown(a)
    }

    "demonstrate configuration of serializers" in {
      val config = ConfigFactory.parseString("""
      #//#serialize-serializers-config
      akka {
        actor {
          serializers {
            java = "akka.serialization.JavaSerializer"
            proto = "akka.remote.serialization.ProtobufSerializer"
            myown = "docs.serialization.MyOwnSerializer"
          }
        }
      }
      #//#serialize-serializers-config
      """)
      val a = ActorSystem("system", config)
      shutdown(a)
    }

    "demonstrate configuration of serialization-bindings" in {
      val config = ConfigFactory.parseString("""
      #//#serialization-bindings-config
      akka {
        actor {
          serializers {
            java = "akka.serialization.JavaSerializer"
            proto = "akka.remote.serialization.ProtobufSerializer"
            myown = "docs.serialization.MyOwnSerializer"
          }

          serialization-bindings {
            "java.lang.String" = java
            "docs.serialization.Customer" = java
            "com.google.protobuf.Message" = proto
            "docs.serialization.MyOwnSerializable" = myown
            "java.lang.Boolean" = myown
          }
        }
      }
      #//#serialization-bindings-config
      """)
      val a = ActorSystem("system", config)
      SerializationExtension(a).serializerFor(classOf[String]).getClass should equal(classOf[JavaSerializer])
      SerializationExtension(a).serializerFor(classOf[Customer]).getClass should equal(classOf[JavaSerializer])
      SerializationExtension(a).serializerFor(classOf[java.lang.Boolean]).getClass should equal(classOf[MyOwnSerializer])
      shutdown(a)
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
      val back = serializer.fromBinary(bytes, manifest = None)

      // Voilá!
      back should equal(original)

      //#programmatic
      shutdown(system)
    }

    "demonstrate serialization of ActorRefs" in {
      val theActorRef: ActorRef = system.deadLetters
      val extendedSystem: ExtendedActorSystem = system.asInstanceOf[ExtendedActorSystem]

      //#actorref-serializer
      // Serialize
      // (beneath toBinary)
      val identifier: String = Serialization.serializedActorPath(theActorRef)

      // Then just serialize the identifier however you like

      // Deserialize
      // (beneath fromBinary)
      val deserializedActorRef = extendedSystem.provider.resolveActorRef(identifier)
      // Then just use the ActorRef
      //#actorref-serializer

      //#external-address
      object ExternalAddress extends ExtensionKey[ExternalAddressExt]

      class ExternalAddressExt(system: ExtendedActorSystem) extends Extension {
        def addressFor(remoteAddr: Address): Address =
          system.provider.getExternalAddressFor(remoteAddr) getOrElse
            (throw new UnsupportedOperationException("cannot send to " + remoteAddr))
      }

      def serializeTo(ref: ActorRef, remote: Address): String =
        ref.path.toSerializationFormatWithAddress(ExternalAddress(extendedSystem).
          addressFor(remote))
      //#external-address
    }

    "demonstrate how to do default Akka serialization of ActorRef" in {
      val theActorSystem: ActorSystem = system

      //#external-address-default
      object ExternalAddress extends ExtensionKey[ExternalAddressExt]

      class ExternalAddressExt(system: ExtendedActorSystem) extends Extension {
        def addressForAkka: Address = system.provider.getDefaultAddress
      }

      def serializeAkkaDefault(ref: ActorRef): String =
        ref.path.toSerializationFormatWithAddress(ExternalAddress(theActorSystem).
          addressForAkka)
      //#external-address-default
    }
  }
}

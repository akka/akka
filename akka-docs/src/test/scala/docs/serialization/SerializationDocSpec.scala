/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.serialization {

  import akka.actor.{ ExtensionId, ExtensionIdProvider }
  import akka.testkit._
  //#imports
  import akka.actor.{ ActorRef, ActorSystem }
  import akka.serialization._
  import com.typesafe.config.ConfigFactory

  //#imports
  import akka.actor.ExtendedActorSystem
  import akka.actor.Extension
  import akka.actor.Address
  import java.nio.charset.StandardCharsets

  //#my-own-serializer
  class MyOwnSerializer extends Serializer {

    // If you need logging here, introduce a constructor that takes an ExtendedActorSystem.
    // class MyOwnSerializer(actorSystem: ExtendedActorSystem) extends Serializer
    // Get a logger using:
    // private val logger = Logging(actorSystem, this)

    // This is whether "fromBinary" requires a "clazz" or not
    def includeManifest: Boolean = true

    // Pick a unique identifier for your Serializer,
    // you've got a couple of billions to choose from,
    // 0 - 40 is reserved by Akka itself
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
    def fromBinary(
      bytes: Array[Byte],
      clazz: Option[Class[_]]): AnyRef = {
      // Put your code that deserializes here
      //#...
      null
      //#...
    }
  }
  //#my-own-serializer

  //#my-own-serializer2
  class MyOwnSerializer2 extends SerializerWithStringManifest {

    val CustomerManifest = "customer"
    val UserManifest = "user"
    val UTF_8 = StandardCharsets.UTF_8.name()

    // Pick a unique identifier for your Serializer,
    // you've got a couple of billions to choose from,
    // 0 - 40 is reserved by Akka itself
    def identifier = 1234567

    // The manifest (type hint) that will be provided in the fromBinary method
    // Use `""` if manifest is not needed.
    def manifest(obj: AnyRef): String =
      obj match {
        case _: Customer ⇒ CustomerManifest
        case _: User     ⇒ UserManifest
      }

    // "toBinary" serializes the given object to an Array of Bytes
    def toBinary(obj: AnyRef): Array[Byte] = {
      // Put the real code that serializes the object here
      obj match {
        case Customer(name) ⇒ name.getBytes(UTF_8)
        case User(name)     ⇒ name.getBytes(UTF_8)
      }
    }

    // "fromBinary" deserializes the given array,
    // using the type hint
    def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = {
      // Put the real code that deserializes here
      manifest match {
        case CustomerManifest ⇒
          Customer(new String(bytes, UTF_8))
        case UserManifest ⇒
          User(new String(bytes, UTF_8))
      }
    }
  }
  //#my-own-serializer2

  trait MyOwnSerializable
  final case class Customer(name: String) extends MyOwnSerializable
  final case class User(name: String) extends MyOwnSerializable

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
      SerializationExtension(a).serializerFor(classOf[String]).getClass should be(classOf[JavaSerializer])
      SerializationExtension(a).serializerFor(classOf[Customer]).getClass should be(classOf[JavaSerializer])
      SerializationExtension(a).serializerFor(classOf[java.lang.Boolean]).getClass should be(classOf[MyOwnSerializer])
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
      back should be(original)

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

      // Then serialize the identifier however you like

      // Deserialize
      // (beneath fromBinary)
      val deserializedActorRef = extendedSystem.provider.resolveActorRef(identifier)
      // Then use the ActorRef
      //#actorref-serializer

      //#external-address
      object ExternalAddress extends ExtensionId[ExternalAddressExt] with ExtensionIdProvider {
        override def lookup() = ExternalAddress

        override def createExtension(system: ExtendedActorSystem): ExternalAddressExt =
          new ExternalAddressExt(system)

        override def get(system: ActorSystem): ExternalAddressExt = super.get(system)
      }

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
      object ExternalAddress extends ExtensionId[ExternalAddressExt] with ExtensionIdProvider {
        override def lookup() = ExternalAddress

        override def createExtension(system: ExtendedActorSystem): ExternalAddressExt =
          new ExternalAddressExt(system)

        override def get(system: ActorSystem): ExternalAddressExt = super.get(system)
      }

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

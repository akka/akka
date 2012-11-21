/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

//#extract-transport
package object akka {
  // needs to be inside the akka package because accessing unsupported API !
  def transportOf(system: actor.ExtendedActorSystem): remote.RemoteTransport =
    system.provider match {
      case r: remote.RemoteActorRefProvider ⇒ r.transport
      case _ ⇒
        throw new UnsupportedOperationException(
          "this method requires the RemoteActorRefProvider to be configured")
    }
}
//#extract-transport

package docs.serialization {

  import org.scalatest.matchers.MustMatchers
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
  import akka.remote.RemoteActorRefProvider

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
            proto = "akka.remote.serialization.ProtobufSerializer"
            myown = "docs.serialization.MyOwnSerializer"
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
      val back = serializer.fromBinary(bytes, manifest = None)

      // Voilá!
      back must equal(original)

      //#programmatic
      system.shutdown()
    }

    "demonstrate serialization of ActorRefs" in {
      val theActorRef: ActorRef = system.deadLetters
      val theActorSystem: ActorSystem = system

      //#actorref-serializer
      // Serialize
      // (beneath toBinary)

      // If there is no transportAddress,
      // it means that either this Serializer isn't called
      // within a piece of code that sets it,
      // so either you need to supply your own,
      // or simply use the local path.
      val identifier: String = Serialization.currentTransportAddress.value match {
        case null    ⇒ theActorRef.path.toString
        case address ⇒ theActorRef.path.toStringWithAddress(address)
      }
      // Then just serialize the identifier however you like

      // Deserialize
      // (beneath fromBinary)
      val deserializedActorRef = theActorSystem actorFor identifier
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
        ref.path.toStringWithAddress(ExternalAddress(theActorSystem).addressFor(remote))
      //#external-address
    }

    "demonstrate how to do default Akka serialization of ActorRef" in {
      val theActorSystem: ActorSystem = system

      //#external-address-default
      object ExternalAddress extends ExtensionKey[ExternalAddressExt]

      class ExternalAddressExt(system: ExtendedActorSystem) extends Extension {
        def addressForAkka: Address = akka.transportOf(system).defaultAddress
      }

      def serializeAkkaDefault(ref: ActorRef): String =
        ref.path.toStringWithAddress(ExternalAddress(theActorSystem).addressForAkka)
      //#external-address-default
    }
  }
}

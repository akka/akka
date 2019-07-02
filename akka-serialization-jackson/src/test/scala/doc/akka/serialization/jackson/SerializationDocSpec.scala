/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.serialization.Serializers
import akka.testkit.TestKit
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike

//#marker-interface
/**
 * Marker interface for messages, events and snapshots that are serialized with Jackson.
 */
trait MySerializable

final case class Message(name: String, nr: Int) extends MySerializable
//#marker-interface

object SerializationDocSpec {
  val config = """
    #//#serialization-bindings
    akka.actor {
      serialization-bindings {
        "com.myservice.MySerializable" = jackson-json
      }
    }
    #//#serialization-bindings
  """

  val configMigration = """
    #//#migrations-conf
    akka.serialization.jackson.migrations {
      "com.myservice.event.ItemAdded" = "com.myservice.event.ItemAddedMigration"
    }
    #//#migrations-conf
  """

  val configMigrationRenamClass = """
    #//#migrations-conf-rename
    akka.serialization.jackson.migrations {
      "com.myservice.event.OrderAdded" = "com.myservice.event.OrderPlacedMigration"
    }
    #//#migrations-conf-rename
  """

  val configSpecific = """
    #//#specific-config
    akka.serialization.jackson.jackson-json {
      serialization-features {
        WRITE_DATES_AS_TIMESTAMPS = off
      }
    }
    akka.serialization.jackson.jackson-cbor {
      serialization-features {
        WRITE_DATES_AS_TIMESTAMPS = on
      }
    }
    #//#specific-config
  """

  val configSeveral = """
    #//#several-config
    akka.actor {
      serializers {
        jackson-json-message = "akka.serialization.jackson.JacksonJsonSerializer"
        jackson-json-event   = "akka.serialization.jackson.JacksonJsonSerializer"
      }
      serialization-identifiers {
        jackson-json-message = 9001
        jackson-json-event = 9002
      }
      serialization-bindings {
        "com.myservice.MyMessage" = jackson-json-message
        "com.myservice.MyEvent" = jackson-json-event
      }
    }
    akka.serialization.jackson {
      jackson-json-message {
        serialization-features {
          WRITE_DATES_AS_TIMESTAMPS = on
        }
      }
      jackson-json-event {
        serialization-features {
          WRITE_DATES_AS_TIMESTAMPS = off
        }
      }
    }
    #//#several-config
  """

  //#polymorphism
  final case class Zoo(primaryAttraction: Animal) extends MySerializable

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(value = classOf[Lion], name = "lion"),
      new JsonSubTypes.Type(value = classOf[Elephant], name = "elephant")))
  sealed trait Animal

  final case class Lion(name: String) extends Animal

  final case class Elephant(name: String, age: Int) extends Animal
  //#polymorphism

  val configDateTime = """
    #//#date-time
    akka.serialization.jackson.serialization-features {
      WRITE_DATES_AS_TIMESTAMPS = on
    }
    #//#date-time
    """

  val configWhitelist = """
    #//#whitelist-class-prefix
    akka.serialization.jackson.whitelist-class-prefix = 
      ["com.myservice.event.OrderAdded", "com.myservice.command"]
    #//#whitelist-class-prefix
  """

}
// FIXME add real tests for the migrations, see EventMigrationTest.java in Lagom

class SerializationDocSpec
    extends TestKit(
      ActorSystem(
        "JacksonJsonSerializerSpec",
        ConfigFactory.parseString(s"""
          akka.actor {
            allow-java-serialization = off
            serialization-bindings {
              "${classOf[MySerializable].getName}" = jackson-json
            }
          }
          """)))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  override def afterAll(): Unit = {
    shutdown()
  }

  private val serialization = SerializationExtension(system)

  def roundTrip(obj: AnyRef): AnyRef = {
    // toBinary
    val bytes = serialization.serialize(obj).get
    val manifest = Serializers.manifestFor(serialization.findSerializerFor(obj), obj)
    val id = serialization.findSerializerFor(obj).identifier

    // fromBinary
    serialization.deserialize(bytes, id, manifest).get
  }

  "serialize trait + object ADT" in {
    import CustomAdtSerializer.Compass
    import CustomAdtSerializer.Direction._

    roundTrip(Compass(North)) should ===(Compass(North))
    roundTrip(Compass(East)) should ===(Compass(East))
    roundTrip(Compass(South)) should ===(Compass(South))
    roundTrip(Compass(West)) should ===(Compass(West))
  }

}

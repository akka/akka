/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package doc.akka.serialization.jackson

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
      "com.myservice.event.OrederAdded" = "com.myservice.event.OrderPlacedMigration"
    }
    #//#migrations-conf-rename
  """
}
// FIXME add real tests for the migrations, see EventMigrationTest.java in Lagom

/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.ObjectNode

class ScalaTestEventMigrationV2 extends JacksonMigration {
  override def currentVersion = 2

  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[ScalaTestMessages.Event2].getName

  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    val root = json.asInstanceOf[ObjectNode]
    root.set[JsonNode]("field1V2", root.get("field1"))
    root.remove("field1")
    root.set[JsonNode]("field2", IntNode.valueOf(17))
    root
  }

}

class ScalaTestEventMigrationV2WithV3 extends JacksonMigration {
  override def currentVersion = 2

  override def supportedForwardVersion: Int = 3

  // Always produce the type of the currentVersion. When fromVersion is lower,
  // transform will lift it. When fromVersion is higher, transform will downcast it.
  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[ScalaTestMessages.Event2].getName

  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    if (fromVersion < 2) {
      val root = json.asInstanceOf[ObjectNode]
      root.set[JsonNode]("field1V2", root.get("field1"))
      root.remove("field1")
      root.set[JsonNode]("field2", IntNode.valueOf(17))
      return root
    }
    if (fromVersion == 3) { // downcast the V3 representation to the V2 representation. A field
      // is renamed.
      val root = json.asInstanceOf[ObjectNode]
      root.set("field2", root.get("field3"))
      root.remove("field3")
      return root
    }
    return json
  }

}

class ScalaTestEventMigrationV3 extends JacksonMigration {
  override def currentVersion = 3

  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[ScalaTestMessages.Event3].getName

  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    if (fromVersion < 2) {
      val root = json.asInstanceOf[ObjectNode]
      root.set("field1V2", root.get("field1"))
      root.remove("field1")
      root.set("field2", IntNode.valueOf(17))
      return root
    }
    if (fromVersion < 3) {
      val root = json.asInstanceOf[ObjectNode]
      root.set("field3", root.get("field2"))
      root.remove("field2")
      return root
    }
    return json
  }

}

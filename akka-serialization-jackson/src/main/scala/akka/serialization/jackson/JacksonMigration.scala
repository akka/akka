/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import com.fasterxml.jackson.databind.JsonNode
import akka.util.unused

/**
 * Data migration of old formats to current format can
 * be implemented in a concrete subclass and configured to
 * be used by the `JacksonSerializer` for a changed class.
 *
 * It is used when deserializing data of older version than the
 * [[JacksonMigration#currentVersion]]. You implement the transformation of the
 * JSON structure in the [[JacksonMigration#transform]] method. If you have changed the
 * class name you should override [[JacksonMigration#transformClassName]] and return
 * current class name.
 */
abstract class JacksonMigration {

  /**
   * Define current version. The first version, when no migration was used,
   * is always 1.
   */
  def currentVersion: Int

  /**
   * Override this method if you have changed the class name. Return
   * current class name.
   */
  def transformClassName(@unused fromVersion: Int, className: String): String =
    className

  /**
   * Implement the transformation of the old JSON structure to the new
   * JSON structure. The `JsonNode` is mutable so you can add and remove fields,
   * or change values. Note that you have to cast to specific sub-classes such
   * as `ObjectNode` and `ArrayNode` to get access to mutators.
   *
   * @param fromVersion the version of the old data
   * @param json the old JSON data
   */
  def transform(fromVersion: Int, json: JsonNode): JsonNode
}

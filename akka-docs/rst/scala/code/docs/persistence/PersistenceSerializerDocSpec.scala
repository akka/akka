/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence

import com.typesafe.config._

import org.scalatest.WordSpec

import akka.actor.ActorSystem
import akka.serialization.{ Serializer, SerializationExtension }

class PersistenceSerializerDocSpec extends WordSpec {

  val customSerializerConfig =
    """
      //#custom-serializer-config
      akka.actor {
        serializers {
          my-payload = "docs.persistence.MyPayloadSerializer"
          my-snapshot = "docs.persistence.MySnapshotSerializer"
        }
        serialization-bindings {
          "docs.persistence.MyPayload" = my-payload
          "docs.persistence.MySnapshot" = my-snapshot
        }
      }
      //#custom-serializer-config
    """.stripMargin

  SerializationExtension(ActorSystem("doc", ConfigFactory.parseString(customSerializerConfig)))
}

class MyPayload
class MySnapshot

class MyPayloadSerializer extends Serializer {
  def identifier: Int = 77124
  def includeManifest: Boolean = false
  def toBinary(o: AnyRef): Array[Byte] = ???
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = ???
}

class MySnapshotSerializer extends Serializer {
  def identifier: Int = 77125
  def includeManifest: Boolean = false
  def toBinary(o: AnyRef): Array[Byte] = ???
  def fromBinary(bytes: Array[Byte], manifest: Option[Class[_]]): AnyRef = ???
}

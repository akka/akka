/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.serialization

import akka.persistence.PersistentRepr
import akka.persistence.journal.Tagged
import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec

class MessageSerializerSpec extends AkkaSpec {

  "Message serializer" should {
    "serialize metadata for persistent repr" in {
      val pr = PersistentRepr("payload", 1L, "pid1").withMetadata("meta")
      val serialization = SerializationExtension(system)
      val deserialzied = serialization.deserialize(serialization.serialize(pr).get, classOf[PersistentRepr]).get
      deserialzied.metadata shouldEqual Some("meta")
    }

    "serialize persistent repr with event" in {
      val pr = PersistentRepr("payload", 1L, "pid1")
      val serialization = SerializationExtension(system)
      val deserialzied = serialization.deserialize(serialization.serialize(pr).get, classOf[PersistentRepr]).get
      deserialzied shouldEqual pr
    }

    "serialize persistent repr with taggedevent" in {
      val pr = PersistentRepr(Tagged("payload", Set("my-tag")), 1L, "pid1")
      val serialization = SerializationExtension(system)
      val deserialzied = serialization.deserialize(serialization.serialize(pr).get, classOf[PersistentRepr]).get
      deserialzied shouldEqual pr
    }

    "serialize a tagged event" in {
      val tagged = Tagged("payload", Set("my-tag"))
      val serialization = SerializationExtension(system)
      val deserialzied = serialization.deserialize(serialization.serialize(tagged).get, classOf[Tagged]).get
      deserialzied shouldEqual tagged
    }

  }

}

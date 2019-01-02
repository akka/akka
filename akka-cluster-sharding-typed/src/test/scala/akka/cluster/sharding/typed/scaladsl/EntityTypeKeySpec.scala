/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.scaladsl

import akka.persistence.typed.PersistenceId
import org.scalatest.Matchers
import org.scalatest.WordSpec

class EntityTypeKeySpec extends WordSpec with Matchers {

  "EntityTypeKey" must {
    "use | as default entityIdSeparator for compatibility with Lagom's scaladsl" in {
      EntityTypeKey[String]("MyType").persistenceIdFrom("abc") should ===(PersistenceId("MyType|abc"))
    }

    "support custom entityIdSeparator for compatibility with Lagom's javadsl" in {
      EntityTypeKey[String]("MyType").withEntityIdSeparator("")
        .persistenceIdFrom("abc") should ===(PersistenceId("MyTypeabc"))
    }

    "support custom entityIdSeparator for compatibility with other naming" in {
      EntityTypeKey[String]("MyType").withEntityIdSeparator("#/#")
        .persistenceIdFrom("abc") should ===(PersistenceId("MyType#/#abc"))
    }

    "not allow | in name because it's the default entityIdSeparator" in {
      intercept[IllegalArgumentException] {
        EntityTypeKey[String]("Invalid | name")
      }
    }

    "not allow custom separator in name" in {
      intercept[IllegalArgumentException] {
        EntityTypeKey[String]("Invalid name").withEntityIdSeparator(" ")
      }
    }

    "not allow | in entityId because it's the default entityIdSeparator" in {
      intercept[IllegalArgumentException] {
        EntityTypeKey[String]("SomeType").persistenceIdFrom("A|B")
      }
    }

    "not allow custom separator in entityId" in {
      intercept[IllegalArgumentException] {
        EntityTypeKey[String]("SomeType").withEntityIdSeparator("#").persistenceIdFrom("A#B")
      }
    }
  }

}

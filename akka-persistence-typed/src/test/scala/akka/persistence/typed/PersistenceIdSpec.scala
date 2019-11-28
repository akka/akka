/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.Matchers
import org.scalatest.WordSpec

class PersistenceIdSpec extends WordSpec with Matchers with LogCapturing {

  "PersistenceId" must {
    "use | as default entityIdSeparator for compatibility with Lagom's scaladsl" in {
      PersistenceId("MyType", "abc") should ===(PersistenceId.ofUniqueId("MyType|abc"))
    }

    "support custom separator for compatibility with Lagom's javadsl" in {
      PersistenceId("MyType", "abc", "") should ===(PersistenceId.ofUniqueId("MyTypeabc"))
    }

    "support custom entityIdSeparator for compatibility with other naming" in {
      PersistenceId("MyType", "abc", "#/#") should ===(PersistenceId.ofUniqueId("MyType#/#abc"))
    }

    "not allow | in entityTypeName because it's the default separator" in {
      intercept[IllegalArgumentException] {
        PersistenceId("Invalid | name", "abc")
      }
    }

    "not allow custom separator in entityTypeName" in {
      intercept[IllegalArgumentException] {
        PersistenceId("Invalid name", "abc", " ")
      }
    }

    "not allow | in entityId because it's the default separator" in {
      intercept[IllegalArgumentException] {
        PersistenceId("SomeType", "A|B")
      }
    }

    "not allow custom separator in entityId" in {
      intercept[IllegalArgumentException] {
        PersistenceId("SomeType", "A#B", "#")
      }
    }
  }

}

/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.sharding.typed.internal

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.charset.StandardCharsets.UTF_8

class Murmur2Spec extends AnyWordSpecLike with Matchers {
  "The Murmur2 hashing" must {
    // expected correct hash values from the kafka murmur2 impl
    // https://github.com/apache/kafka/blob/trunk/clients/src/main/java/org/apache/kafka/common/utils/Utils.java#L500
    Seq(
      "1" -> -1993445489,
      "12" -> 126087238,
      "123" -> -267702483,
      "1234" -> -1614185708,
      "12345" -> -1188365604
    ).foreach { case (string, expectedHash) =>
      s"calculate the correct checksum for '$string'" in {
        Murmur2.murmur2(string.getBytes(UTF_8)) should ===(expectedHash)
      }
    }
  }
}

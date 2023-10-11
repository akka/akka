/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.internal

import java.nio.charset.StandardCharsets

import org.apache.commons.codec.digest.MurmurHash3
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class Hash128Spec extends AnyWordSpec with Matchers {
  "128 bit hashing" must {
    "be same as MurmurHash3.hash128x64Internal" in {
      // note that MurmurHash3 is from commons-codec test dependency
//      val rnd = new Random
      val data = "abc"
      val dataBytes = data.getBytes(StandardCharsets.UTF_8)
      withClue(s"$data: ") {
        val expected = MurmurHash3.hash128x64(dataBytes)
        Hash128.hash128x64(dataBytes) shouldBe (expected(0), expected(1))
      }
    }
  }

}

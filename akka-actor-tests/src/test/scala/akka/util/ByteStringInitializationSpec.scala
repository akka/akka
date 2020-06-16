/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ByteStringInitializationSpec extends AnyWordSpec with Matchers {
  "ByteString intialization" should {
    "not get confused by initializing CompactByteString before ByteString" in {
      ByteString.empty should not be (null)
      CompactByteString.empty should not be (null)
      ByteString.empty should not be (null)
    }
  }
}

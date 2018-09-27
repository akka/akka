/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import akka.util.ByteString
import org.scalatest.{ Matchers, WordSpec }

class MessageSpec extends WordSpec with Matchers {
  "The Message" should {
    "parse a response that is truncated mid-message" in {
      val bytes = ByteString(0, 4, -125, -128, 0, 1, 0, 48, 0, 0, 0, 0, 4, 109, 97, 110, 121, 4, 98, 122, 122, 116, 3, 110, 101, 116, 0, 0, 28, 0, 1)
      val msg = Message.parse(bytes)
    }
  }

}

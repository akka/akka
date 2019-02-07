/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.util
import java.nio.charset.StandardCharsets

import org.scalatest.Matchers
import org.scalatest.WordSpec

class AsciiStringCopySpec extends WordSpec with Matchers {

  "The copyUSAsciiStrToBytes optimization" must {

    "copy string internal representation successfully" in {
      val ascii = "abcdefghijklmnopqrstuvxyz"
      val byteArray = new Array[Byte](ascii.length)
      Unsafe.copyUSAsciiStrToBytes(ascii, byteArray)
      new String(byteArray, StandardCharsets.US_ASCII) should ===(ascii)
    }
  }

  "The fast hash optimization" must {

    "hash strings successfully" in {
      Unsafe.fastHash("abcdefghijklmnopqrstuvxyz")
    }

  }

}

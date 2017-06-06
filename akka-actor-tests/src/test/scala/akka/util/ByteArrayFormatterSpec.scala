/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.util

import akka.testkit.AkkaSpec
import akka.util.Utf8Verifier._
import akka.util.ByteArrayPrettyPrinter._
import scala.util.Random

class ByteArrayFormatterSpec extends AkkaSpec {

  def recognizeAsText(s: String, encoding: String): Unit = {
    val b = s.getBytes(encoding)
    isUtf8Text(b, b.length) must be(true)
  }

  "Utf8Verifier" must {

    "correctly recognize ASCII and UTF-8 encoded text" in {
      recognizeAsText("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890", "UTF-8")
      recognizeAsText("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ1234567890", "US-ASCII")
      recognizeAsText("árvíztűrőütvefúrógépÁRVÍZTŰRŐÜTVEFÚRÓGÉP", "UTF-8")
      recognizeAsText("árvíztűrőütvefúrógépÁRVÍZTŰRŐÜTVEFÚRÓGÉP", "US-ASCII")
      recognizeAsText("árvíztűrőütvefúrógépÁRVÍZTŰRŐÜTVEFÚRÓGÉP", "US-ASCII")
      recognizeAsText("'\"+!%/=()\\|<>#&@{}[]_-.:,?;", "US-ASCII")
    }

    "correctly bail out on binary data" in {
      val TestArrayLength = 10
      val buffer = Array.fill[Byte](TestArrayLength)(0)
      // Starting from known seed
      val rng = new Random(42)
      for (_ ← 1 to 100) {
        rng.nextBytes(buffer)
        isUtf8Text(buffer, TestArrayLength) must be(false)
      }
    }

  }

  "ByteArrayPrettyPrinter" must {

    "correctly format strings in byte arrays" in {
      prettyPrint("abc123".getBytes("UTF-8")) must be === "[\"abc123\"]"

    }

    "correctly format long strings in byte arrays" in {
      prettyPrint("1234567890123456789012345678901234567890".getBytes("UTF-8")) must be ===
        "[\"12345678901234567890123456789012\" ... (and 8 bytes)]"
    }

    "correctly format binary data in byte arrays" in {
      prettyPrint(Array.tabulate[Byte](16)(_.asInstanceOf[Byte])) must be ===
        "[00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F]"
    }

    "correctly format long binary data in byte arrays" in {
      prettyPrint(Array.tabulate[Byte](32)(_.asInstanceOf[Byte]), maximumLength = 18) must be ===
        "[00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 ... (and 14 bytes)]"
    }

  }

}

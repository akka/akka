/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import org.scalatest.{ WordSpec, Matchers }
import akka.util.ByteString
import akka.http.model.headers.`Content-Length`
import akka.http.parsing.SpecializedHeaderValueParsers.ContentLengthParser

class ContentLengthHeaderParserSpec extends WordSpec with Matchers {

  "specialized ContentLength parser" should {
    "accept zero" in {
      parse("0") === 0L
    }
    "accept positive value" in {
      parse("43234398") === 43234398L
    }
    "accept positive value > Int.MaxValue <= Long.MaxValue" in {
      parse("274877906944") === 274877906944L
      parse("9223372036854775807") === 9223372036854775807L // Long.MaxValue
    }
    "don't accept positive value > Long.MaxValue" in {
      a[ParsingException] should be thrownBy parse("9223372036854775808") // Long.MaxValue + 1
      a[ParsingException] should be thrownBy parse("92233720368547758070") // Long.MaxValue * 10 which is 0 taken overflow into account
      a[ParsingException] should be thrownBy parse("92233720368547758080") // (Long.MaxValue + 1) * 10 which is 0 taken overflow into account
    }
  }

  def parse(bigint: String): Long = {
    val (`Content-Length`(length), _) = ContentLengthParser(ByteString(bigint + "\r\n").compact, 0, _ ⇒ ())
    length
  }

}

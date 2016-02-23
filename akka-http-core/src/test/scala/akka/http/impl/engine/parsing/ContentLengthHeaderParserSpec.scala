/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.parsing

import org.scalatest.{ WordSpec, Matchers }
import akka.util.ByteString
import akka.http.scaladsl.model.headers.`Content-Length`
import akka.http.impl.engine.parsing.SpecializedHeaderValueParsers.ContentLengthParser

class ContentLengthHeaderParserSpec extends WordSpec with Matchers {

  "specialized ContentLength parser" should {
    "accept zero" in {
      parse("0") shouldEqual 0L
    }
    "accept positive value" in {
      parse("43234398") shouldEqual 43234398L
    }
    "accept positive value > Int.MaxValue <= Long.MaxValue" in {
      parse("274877906944") shouldEqual 274877906944L
      parse("9223372036854775807") shouldEqual 9223372036854775807L // Long.MaxValue
    }
    "don't accept positive value > Long.MaxValue" in {
      a[ParsingException] should be thrownBy parse("9223372036854775808") // Long.MaxValue + 1
      a[ParsingException] should be thrownBy parse("92233720368547758070") // Long.MaxValue * 10 which is 0 taken overflow into account
      a[ParsingException] should be thrownBy parse("92233720368547758080") // (Long.MaxValue + 1) * 10 which is 0 taken overflow into account
    }
  }

  def parse(bigint: String): Long = {
    val (`Content-Length`(length), _) = ContentLengthParser(null, ByteString(bigint + "\r\n").compact, 0, _ ⇒ ())
    length
  }

}

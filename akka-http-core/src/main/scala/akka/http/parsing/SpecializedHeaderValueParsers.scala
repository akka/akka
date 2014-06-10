/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.parsing

import scala.annotation.tailrec
import akka.util.ByteString
import akka.http.model.{ HttpHeader, ErrorInfo }
import akka.http.model.headers.`Content-Length`
import akka.http.model.parser.CharacterClasses._

/**
 * INTERNAL API
 */
private object SpecializedHeaderValueParsers {
  import HttpHeaderParser._

  def specializedHeaderValueParsers = Seq(ContentLengthParser)

  object ContentLengthParser extends HeaderValueParser("Content-Length", maxValueCount = 1) {
    def apply(input: ByteString, valueStart: Int, warnOnIllegalHeader: ErrorInfo ⇒ Unit): (HttpHeader, Int) = {
      @tailrec def recurse(ix: Int = valueStart, result: Long = 0): (HttpHeader, Int) = {
        val c = byteChar(input, ix)
        if (DIGIT(c) && result >= 0) recurse(ix + 1, result * 10 + c - '0')
        else if (WSP(c)) recurse(ix + 1, result)
        else if (c == '\r' && byteChar(input, ix + 1) == '\n' && result >= 0) (`Content-Length`(result), ix + 2)
        else fail("Illegal `Content-Length` header value")
      }
      recurse()
    }
  }
}

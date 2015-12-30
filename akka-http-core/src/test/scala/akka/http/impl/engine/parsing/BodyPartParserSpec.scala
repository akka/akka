package akka.http.impl.engine.parsing

/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.http.ParserSettings.CookieParsingMode.RFC6265
import akka.http.scaladsl.model.Uri.ParsingMode.{ Strict, Relaxed }
import org.scalatest.{ WordSpec, Matchers }

class BodyPartParserSpec extends WordSpec with Matchers {

  "Body Part Parser" should {
    "read reference.conf" in {
      BodyPartParser.defaultSettings.maxHeaderNameLength shouldEqual 64
      BodyPartParser.defaultSettings.maxHeaderValueLength shouldEqual 8192
      BodyPartParser.defaultSettings.maxHeaderCount shouldEqual 64
      BodyPartParser.defaultSettings.illegalHeaderWarnings shouldEqual true
      BodyPartParser.defaultSettings.headerValueCacheLimit shouldEqual 12
      BodyPartParser.defaultSettings.uriParsingMode shouldEqual Strict
      BodyPartParser.defaultSettings.cookieParsingMode shouldEqual RFC6265
    }
  }

}
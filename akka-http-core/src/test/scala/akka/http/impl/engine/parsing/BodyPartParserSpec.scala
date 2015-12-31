package akka.http.impl.engine.parsing

/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

import akka.actor.ActorSystem
import akka.http.ParserSettings
import akka.http.ParserSettings.CookieParsingMode.RFC6265
import akka.http.scaladsl.model.Uri.ParsingMode.{ Strict, Relaxed }
import com.typesafe.config.{ ConfigFactory, Config }
import org.scalatest.{ WordSpec, Matchers }

class BodyPartParserSpec extends WordSpec with Matchers {
  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = ERROR
    akka.http.parsing.max-header-name-length = 111
    akka.http.parsing.max-header-value-length = 111
    akka.http.parsing.max-header-count = 111
    akka.http.parsing.illegal-header-warnings = on
    akka.http.parsing.header-value-cache-limit = 12
    akka.http.parsing.uri-parsing-mode = strict
    akka.http.parsing.cookie-parsing-mode = rfc6265
                                                   """)
  val system = ActorSystem(getClass.getSimpleName, testConf)

  "Body Part Parser" should {
    "read reference.conf" in {
      val bodyPartParser = BodyPartParser.Settings(ParserSettings(system))
      bodyPartParser.maxHeaderNameLength shouldEqual 111
      bodyPartParser.maxHeaderValueLength shouldEqual 111
      bodyPartParser.maxHeaderCount shouldEqual 111
      bodyPartParser.illegalHeaderWarnings shouldEqual true
      bodyPartParser.headerValueCacheLimit shouldEqual 12
      bodyPartParser.uriParsingMode shouldEqual Strict
      bodyPartParser.cookieParsingMode shouldEqual RFC6265
    }
  }

}
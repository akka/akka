/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.scaladsl.model._
import org.scalatest.{ FreeSpec, MustMatchers }

class HeaderSpec extends FreeSpec with MustMatchers {
  "ModeledCompanion should" - {
    "provide parseFromValueString method" - {
      "successful parse run" in {
        headers.`Cache-Control`.parseFromValueString("private, no-cache, no-cache=Set-Cookie, proxy-revalidate, s-maxage=1000") mustEqual
          Right(headers.`Cache-Control`(
            CacheDirectives.`private`(),
            CacheDirectives.`no-cache`,
            CacheDirectives.`no-cache`("Set-Cookie"),
            CacheDirectives.`proxy-revalidate`,
            CacheDirectives.`s-maxage`(1000)))
      }
      "failing parse run" in {
        val Left(List(ErrorInfo(summary, detail))) = headers.`Last-Modified`.parseFromValueString("abc")
        summary mustEqual "Illegal HTTP header 'Last-Modified': Invalid input 'a', expected 'S', 'M', 'T', 'W', 'F' or '0' (line 1, column 1)"
        detail mustEqual
          """abc
            |^""".stripMargin

      }
    }
  }

  "MediaType should" - {
    "provide parse method" - {
      "successful parse run" in {
        MediaType.parse("application/gnutar") mustEqual Right(MediaTypes.`application/gnutar`)
      }
      "failing parse run" in {
        val Left(List(ErrorInfo(summary, detail))) = MediaType.parse("application//gnutar")
        summary mustEqual "Illegal HTTP header 'Content-Type': Invalid input '/', expected tchar (line 1, column 13)"
        detail mustEqual
          """application//gnutar
            |            ^""".stripMargin
      }
    }
  }

  "ContentType should" - {
    "provide parse method" - {
      "successful parse run" in {
        ContentType.parse("text/plain; charset=UTF8") mustEqual Right(MediaTypes.`text/plain`.withCharset(HttpCharsets.`UTF-8`))
      }
      "failing parse run" in {
        val Left(List(ErrorInfo(summary, detail))) = ContentType.parse("text/plain, charset=UTF8")
        summary mustEqual "Illegal HTTP header 'Content-Type': Invalid input ',', expected tchar, '\\r', WSP, ';' or 'EOI' (line 1, column 11)"
        detail mustEqual
          """text/plain, charset=UTF8
            |          ^""".stripMargin
      }
    }
  }
}

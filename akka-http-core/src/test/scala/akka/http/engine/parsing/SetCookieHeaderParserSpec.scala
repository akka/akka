/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.engine.parsing

import org.scalatest.{ WordSpec, Matchers }
import akka.util.ByteString
import akka.http.model.{ ErrorInfo, HttpHeader }
import akka.http.model.headers._
import akka.http.model.parser.HeaderParser
import akka.http.util.DateTime

class SetCookieHeaderParserSpec extends WordSpec with Matchers {

  "Set-Cookie parser" should {
    "parse cookies" in {
      parse("SID=\"31d4d96e407aad42\"") shouldEqual
        Right(`Set-Cookie`(HttpCookie("SID", "31d4d96e407aad42")))
      parse("SID=31d4d96e407aad42; Domain=example.com; Path=/") shouldEqual
        Right(`Set-Cookie`(HttpCookie("SID", "31d4d96e407aad42", path = Some("/"), domain = Some("example.com"))))
      parse("lang=en-US; Expires=Wed, 09 Jun 2021 10:18:14 GMT; Path=/hello") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "en-US", expires = Some(DateTime(2021, 6, 9, 10, 18, 14)), path = Some("/hello"))))
      parse("lang=; Expires=Sun, 06 Nov 1994 08:49:37 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(1994, 11, 6, 8, 49, 37)))))
      parse("name=123; Max-Age=12345; Secure") shouldEqual
        Right(`Set-Cookie`(HttpCookie("name", "123", maxAge = Some(12345), secure = true)))
      parse("name=123; HttpOnly; fancyPants") shouldEqual
        Right(`Set-Cookie`(HttpCookie("name", "123", httpOnly = true, extension = Some("fancyPants"))))
      parse("foo=bar; domain=example.com; Path=/this is a path with blanks; extension with blanks") shouldEqual
        Right(`Set-Cookie`(HttpCookie("foo", "bar", domain = Some("example.com"), path = Some("/this is a path with blanks"),
          extension = Some("extension with blanks"))))
    }

    "parse expiry dates from each day of the week" in {
      parse("lang=; Expires=Mon, 08 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 8, 0, 42, 55)))))
      parse("lang=; Expires=Tue, 09 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 9, 0, 42, 55)))))
      parse("lang=; Expires=Wed, 10 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 10, 0, 42, 55)))))
      parse("lang=; Expires=Thu, 11 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 11, 0, 42, 55)))))
      parse("lang=; Expires=Fri, 12 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 12, 0, 42, 55)))))
      parse("lang=; Expires=Sat, 13 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 13, 0, 42, 55)))))
      parse("lang=; Expires=Sun, 14 Dec 2014 00:42:55 GMT") shouldEqual
        Right(`Set-Cookie`(HttpCookie("lang", "", expires = Some(DateTime(2014, 12, 14, 0, 42, 55)))))
    }

    "parse cookie examples from Play" in {
      parse("PLAY_FLASH=\"success=found\"; Path=/; HTTPOnly") shouldEqual
        Right(`Set-Cookie`(HttpCookie("PLAY_FLASH", "success=found", path = Some("/"), httpOnly = true)))
      parse("PLAY_FLASH=; Expires=Sun, 07 Dec 2014 22:48:47 GMT; Path=/; HTTPOnly") shouldEqual
        Right(`Set-Cookie`(HttpCookie("PLAY_FLASH", "", expires = Some(DateTime(2014, 12, 7, 22, 48, 47)), path = Some("/"), httpOnly = true)))
    }

  }

  def parse(setCookieString: String): Either[ErrorInfo, HttpHeader] = {
    HeaderParser.parseHeader(RawHeader("Set-Cookie", setCookieString))
  }

}

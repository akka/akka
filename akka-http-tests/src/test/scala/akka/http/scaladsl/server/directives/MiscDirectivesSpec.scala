/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.{ Await, Promise }
import scala.concurrent.duration._
import scala.util.Try
import akka.http.scaladsl.model._
import headers._
import java.net.InetAddress

class MiscDirectivesSpec extends RoutingSpec {

  "the extractClientIP directive" should {
    "extract from a X-Forwarded-For header" in {
      Get() ~> addHeaders(`X-Forwarded-For`(remoteAddress("2.3.4.5")), RawHeader("x-real-ip", "1.2.3.4")) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "2.3.4.5" }
    }
    "extract from a Remote-Address header" in {
      Get() ~> addHeaders(`X-Real-Ip`(remoteAddress("1.2.3.4")), `Remote-Address`(remoteAddress("5.6.7.8"))) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "5.6.7.8" }
    }
    "extract from a X-Real-IP header" in {
      Get() ~> addHeader(`X-Real-Ip`(remoteAddress("1.2.3.4"))) ~> {
        extractClientIP { echoComplete }
      } ~> check { responseAs[String] shouldEqual "1.2.3.4" }
    }
  }

  "the selectPreferredLanguage directive" should {
    "Accept-Language: de, en" test { selectFrom ⇒
      selectFrom("de", "en") shouldEqual "de"
      selectFrom("en", "de") shouldEqual "en"
    }
    "Accept-Language: en, de;q=.5" test { selectFrom ⇒
      selectFrom("de", "en") shouldEqual "en"
      selectFrom("en", "de") shouldEqual "en"
    }
    "Accept-Language: en;q=.5, de" test { selectFrom ⇒
      selectFrom("de", "en") shouldEqual "de"
      selectFrom("en", "de") shouldEqual "de"
    }
    "Accept-Language: en-US, en;q=.7, *;q=.1, de;q=.5" test { selectFrom ⇒
      selectFrom("en", "en-US") shouldEqual "en-US"
      selectFrom("de", "en") shouldEqual "en"
      selectFrom("de", "hu") shouldEqual "de"
      selectFrom("de-DE", "hu") shouldEqual "de-DE"
      selectFrom("hu", "es") shouldEqual "hu"
      selectFrom("es", "hu") shouldEqual "es"
    }
    "Accept-Language: en, *;q=.5, de;q=0" test { selectFrom ⇒
      selectFrom("es", "de") shouldEqual "es"
      selectFrom("de", "es") shouldEqual "es"
      selectFrom("es", "en") shouldEqual "en"
    }
    "Accept-Language: en, *;q=0" test { selectFrom ⇒
      selectFrom("es", "de") shouldEqual "es"
      selectFrom("de", "es") shouldEqual "de"
      selectFrom("es", "en") shouldEqual "en"
    }
  }

  implicit class AddStringToIn(acceptLanguageHeaderString: String) {
    def test(body: ((String*) ⇒ String) ⇒ Unit): Unit =
      s"properly handle `$acceptLanguageHeaderString`" in {
        val Array(name, value) = acceptLanguageHeaderString.split(':')
        val acceptLanguageHeader = HttpHeader.parse(name.trim, value) match {
          case HttpHeader.ParsingResult.Ok(h: `Accept-Language`, Nil) ⇒ h
          case result ⇒ fail(result.toString)
        }
        body { availableLangs ⇒
          val selected = Promise[String]()
          val first = Language(availableLangs.head)
          val more = availableLangs.tail.map(Language(_))
          Get() ~> addHeader(acceptLanguageHeader) ~> {
            selectPreferredLanguage(first, more: _*) { lang ⇒
              complete(lang.toString)
            }
          } ~> check(selected.complete(Try(responseAs[String])))
          Await.result(selected.future, 1.second)
        }
      }
  }

  def remoteAddress(ip: String) = RemoteAddress(InetAddress.getByName(ip))
}

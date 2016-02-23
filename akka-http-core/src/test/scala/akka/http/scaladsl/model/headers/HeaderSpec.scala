/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.util._
import org.scalatest._
import java.net.InetAddress
import akka.http.scaladsl.model._

class HeaderSpec extends FreeSpec with Matchers {
  "ModeledCompanion should" - {
    "provide parseFromValueString method" - {
      "successful parse run" in {
        headers.`Cache-Control`.parseFromValueString("private, no-cache, no-cache=Set-Cookie, proxy-revalidate, s-maxage=1000") shouldEqual
          Right(headers.`Cache-Control`(
            CacheDirectives.`private`(),
            CacheDirectives.`no-cache`,
            CacheDirectives.`no-cache`("Set-Cookie"),
            CacheDirectives.`proxy-revalidate`,
            CacheDirectives.`s-maxage`(1000)))
      }
      "failing parse run" in {
        val Left(List(ErrorInfo(summary, detail))) = headers.`Last-Modified`.parseFromValueString("abc")
        summary shouldEqual "Illegal HTTP header 'Last-Modified': Invalid input 'a', expected IMF-fixdate, asctime-date or '0' (line 1, column 1)"
        detail shouldEqual
          """abc
            |^""".stripMarginWithNewline("\n")

      }
    }
  }

  "MediaType should" - {
    "provide parse method" - {
      "successful parse run" in {
        MediaType.parse("application/gnutar") shouldEqual Right(MediaTypes.`application/gnutar`)
      }
      "failing parse run" in {
        val Left(List(ErrorInfo(summary, detail))) = MediaType.parse("application//gnutar")
        summary shouldEqual "Illegal HTTP header 'Content-Type': Invalid input '/', expected subtype (line 1, column 13)"
        detail shouldEqual
          """application//gnutar
            |            ^""".stripMarginWithNewline("\n")
      }
    }
  }

  "ContentType should" - {
    "provide parse method" - {
      "successful parse run" in {
        ContentType.parse("text/plain; charset=UTF8") shouldEqual Right(MediaTypes.`text/plain`.withCharset(HttpCharsets.`UTF-8`))
      }
      "failing parse run" in {
        val Left(List(ErrorInfo(summary, detail))) = ContentType.parse("text/plain, charset=UTF8")
        summary shouldEqual "Illegal HTTP header 'Content-Type': Invalid input ',', expected tchar, OWS, ws or 'EOI' (line 1, column 11)"
        detail shouldEqual
          """text/plain, charset=UTF8
            |          ^""".stripMarginWithNewline("\n")
      }
    }
  }

  "All request headers should" - {

    "render in request" in {
      val requestHeaders = Vector[HttpHeader](
        Accept(MediaRanges.`*/*`),
        `Accept-Charset`(HttpCharsetRange(HttpCharsets.`UTF-8`)),
        `Accept-Encoding`(HttpEncodingRange(HttpEncodings.gzip)),
        `Accept-Language`(LanguageRange(Language("sv_SE"))),
        `Access-Control-Request-Headers`("Host"),
        `Access-Control-Request-Method`(HttpMethods.GET),
        Authorization(BasicHttpCredentials("johan", "correcthorsebatterystaple")),
        `Cache-Control`(CacheDirectives.`max-age`(3000)),
        Connection("upgrade"),
        `Content-Length`(2000),
        `Content-Disposition`(ContentDispositionTypes.inline),
        `Content-Encoding`(HttpEncodings.gzip),
        `Content-Type`(ContentTypes.`text/xml(UTF-8)`),
        Cookie("cookie", "with-chocolate"),
        Date(DateTime(2016, 2, 4, 9, 9, 0)),
        Expect.`100-continue`,
        Host("example.com"),
        `If-Match`(EntityTag("hash")),
        `If-Modified-Since`(DateTime(2016, 2, 4, 9, 9, 0)),
        `If-None-Match`(EntityTagRange(EntityTag("hashhash"))),
        `If-Range`(DateTime(2016, 2, 4, 9, 9, 0)),
        `If-Unmodified-Since`(DateTime(2016, 2, 4, 9, 9, 0)),
        Link(Uri("http://example.com"), LinkParams.`title*`("example")),
        Origin(HttpOrigin("http", Host("example.com"))),
        `Proxy-Authorization`(BasicHttpCredentials("johan", "correcthorsebatterystaple")),
        Range(RangeUnits.Bytes, Vector(ByteRange(1, 1024))),
        Referer(Uri("http://example.com/")),
        `Sec-WebSocket-Protocol`(Vector("chat", "superchat")),
        `Sec-WebSocket-Key`("dGhlIHNhbXBsZSBub25jZQ"),
        `Sec-WebSocket-Version`(Vector(13)),
        `Transfer-Encoding`(TransferEncodings.chunked),
        Upgrade(Vector(UpgradeProtocol("HTTP", Some("2.0")))),
        `User-Agent`("Akka HTTP Client 2.4"),
        `X-Forwarded-For`(RemoteAddress(InetAddress.getByName("192.168.0.1"))),
        `X-Real-Ip`(RemoteAddress(InetAddress.getByName("192.168.1.1"))))

      requestHeaders.foreach { header ⇒
        header shouldBe 'renderInRequests
      }
    }
  }

  "All response headers should" - {

    "render in response" in {
      val responseHeaders = Vector[HttpHeader](
        `Accept-Ranges`(RangeUnits.Bytes),
        `Access-Control-Allow-Credentials`(true),
        `Access-Control-Allow-Headers`("X-Custom"),
        `Access-Control-Allow-Methods`(HttpMethods.GET),
        `Access-Control-Allow-Origin`(HttpOrigin("http://example.com")),
        `Access-Control-Expose-Headers`("X-Custom"),
        `Access-Control-Max-Age`(2000),
        Age(2000),
        Allow(HttpMethods.GET),
        `Cache-Control`(CacheDirectives.`no-cache`),
        Connection("close"),
        `Content-Length`(2000),
        `Content-Disposition`(ContentDispositionTypes.inline),
        `Content-Encoding`(HttpEncodings.gzip),
        `Content-Range`(ContentRange.Default(1, 20, None)),
        `Content-Type`(ContentTypes.`text/xml(UTF-8)`),
        Date(DateTime(2016, 2, 4, 9, 9, 0)),
        ETag("suchhashwow"),
        Expires(DateTime(2016, 2, 4, 9, 9, 0)),
        `Last-Modified`(DateTime(2016, 2, 4, 9, 9, 0)),
        Link(Uri("http://example.com"), LinkParams.`title*`("example")),
        Location(Uri("http://example.com")),
        `Proxy-Authenticate`(HttpChallenge("Basic", "example.com")),
        `Sec-WebSocket-Accept`("dGhlIHNhbXBsZSBub25jZQ"),
        `Sec-WebSocket-Extensions`(Vector(WebSocketExtension("foo"))),
        `Sec-WebSocket-Version`(Vector(13)),
        Server("Akka-HTTP/2.4"),
        `Set-Cookie`(HttpCookie("sessionId", "b0eb8b8b3ad246")),
        `Transfer-Encoding`(TransferEncodings.chunked),
        Upgrade(Vector(UpgradeProtocol("HTTP", Some("2.0")))),
        `WWW-Authenticate`(HttpChallenge("Basic", "example.com")))

      responseHeaders.foreach { header ⇒
        header shouldBe 'renderInResponses
      }
    }
  }
}

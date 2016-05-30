/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.http.scaladsl.server.ContentNegotiator.Alternative
import akka.util.ByteString
import org.scalatest.{ Matchers, FreeSpec }
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import MediaTypes._
import HttpCharsets._

class ContentNegotiationSpec extends FreeSpec with Matchers {
  "Content Negotiation should work properly for requests with header(s)" - {

    "(without headers)" test { accept ⇒
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain` withCharset `UTF-16`)
      accept(`text/plain`, `text/html`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/html`, `text/plain`) should select(`text/html` withCharset `UTF-8`)
    }

    "Accept: */*" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain` withCharset `UTF-16`)
    }

    "Accept: */*;q=.8" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain` withCharset `UTF-16`)
    }

    "Accept: text/*" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/xml` withCharset `UTF-16`) should select(`text/xml` withCharset `UTF-16`)
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*;q=.8" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/xml` withCharset `UTF-16`) should select(`text/xml` withCharset `UTF-16`)
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*;q=0" test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/xml` withCharset `UTF-16`) should reject
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*, application/json;q=0.8, text/plain;q=0.5" test { accept ⇒
      accept(`text/plain`, `application/json`) should select(`application/json`)
    }

    "Accept-Charset: UTF-16" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "manually created Accept-Charset: UTF-16" in testHeaders(headers.`Accept-Charset`(Vector(HttpCharsets.`UTF-16`.toRange))) { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept-Charset: UTF-16, UTF-8" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain` withCharset `UTF-8`)
    }

    "Accept-Charset: UTF-8;q=.2, UTF-16" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain` withCharset `UTF-8`)
    }

    "Accept-Charset: ISO-8859-1;q=.2" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `ISO-8859-1`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept-Charset: latin1;q=.1, UTF-8;q=.2" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain` withCharset `UTF-8`)
    }

    "Accept-Charset: *" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain` withCharset `UTF-16`)
    }

    "Accept-Charset: *;q=0" test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/plain` withCharset `UTF-16`) should reject
    }

    "Accept-Charset: *;q=0.1" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `UTF-8`)
      accept(`image/gif`) should select(`image/gif`)
    }

    "Accept-Charset: us;q=0.1,*;q=0" test { accept ⇒
      accept(`text/plain`) should select(`text/plain` withCharset `US-ASCII`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept-Charset: UTF-8, *;q=0.8, us;q=0.1" test { accept ⇒
      accept(
        `text/plain` withCharset `US-ASCII`,
        `text/plain` withCharset `ISO-8859-1`) should select(`text/plain` withCharset `ISO-8859-1`)
    }

    "Accept: text/xml, text/html;q=.5" test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/xml`) should select(`text/xml` withCharset `UTF-8`)
      accept(`text/html`) should select(`text/html` withCharset `UTF-8`)
      accept(`text/html`, `text/xml`) should select(`text/xml` withCharset `UTF-8`)
      accept(`text/xml`, `text/html`) should select(`text/xml` withCharset `UTF-8`)
      accept(`text/plain`, `text/xml`) should select(`text/xml` withCharset `UTF-8`)
      accept(`text/plain`, `text/html`) should select(`text/html` withCharset `UTF-8`)
    }

    """Accept: text/html, text/plain;q=0.8, application/*;q=.5, *;q= .2
      |Accept-Charset: UTF-16""" test { accept ⇒
      accept(`text/plain`, `text/html`, `audio/ogg`) should select(`text/html` withCharset `UTF-16`)
      accept(`text/plain`, `text/html` withCharset `UTF-8`, `audio/ogg`) should select(`text/plain` withCharset `UTF-16`)
      accept(`audio/ogg`, `application/javascript`, `text/plain` withCharset `UTF-8`) should select(`application/javascript` withCharset `UTF-16`)
      accept(`image/gif`, `application/javascript`) should select(`application/javascript` withCharset `UTF-16`)
      accept(`image/gif`, `audio/ogg`) should select(`image/gif`)
    }

    "Accept: text/xml, text/plain" test { accept ⇒
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain` withCharset `UTF-16`)
      accept(`text/plain`, `text/xml`) should select(`text/plain` withCharset `UTF-8`)
      accept(`text/xml`, `text/plain`) should select(`text/xml` withCharset `UTF-8`)
    }
  }

  def testHeaders[U](headers: HttpHeader*)(body: ((Alternative*) ⇒ Option[ContentType]) ⇒ U): U = {
    val request = HttpRequest(headers = headers.toVector)
    body { alternatives ⇒
      import scala.concurrent.ExecutionContext.Implicits.global

      // creates a pseudo marshaller for X, that applies for all the given content types
      trait X
      object X extends X
      implicit val marshallers: ToEntityMarshaller[X] =
        Marshaller.oneOf(alternatives map {
          case Alternative.ContentType(ct) ⇒ Marshaller.withFixedContentType(ct)((s: X) ⇒ HttpEntity(ct, ByteString("The X")))
          case Alternative.MediaType(mt)   ⇒ Marshaller.withOpenCharset(mt)((s: X, cs) ⇒ HttpEntity(mt withCharset cs, "The X"))
        }: _*)

      Await.result(Marshal(X).toResponseFor(request)
        .fast.map(response ⇒ Some(response.entity.contentType))
        .fast.recover { case _: Marshal.UnacceptableResponseContentTypeException ⇒ None }, 1.second)
    }
  }

  def reject = equal(None)
  def select(contentType: ContentType) = equal(Some(contentType))

  implicit class AddStringToIn(example: String) {
    def test(body: ((Alternative*) ⇒ Option[ContentType]) ⇒ Unit): Unit = example in {
      val headers =
        if (example != "(without headers)") {
          example.stripMarginWithNewline("\n").split('\n').toList map { rawHeader ⇒
            val Array(name, value) = rawHeader.split(':')
            HttpHeader.parse(name.trim, value) match {
              case HttpHeader.ParsingResult.Ok(header, Nil) ⇒ header
              case result                                   ⇒ fail(result.errors.head.formatPretty)
            }
          }
        } else Nil

      testHeaders(headers: _*)(accept ⇒ body(accept))
    }
  }
}


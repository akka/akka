/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import akka.http.scaladsl.model.MediaType.Encoding

import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.{ Matchers, FreeSpec }
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model._
import akka.http.impl.util._
import MediaTypes._
import HttpCharsets._

class ContentNegotiationSpec extends FreeSpec with Matchers {
  "Content Negotiation should work properly for requests with header(s)" - {

    "(without headers)" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept: */*" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept: */*;q=.8" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept: text/*" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/xml` withCharset `UTF-16`) should select(`text/xml`, `UTF-16`)
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*;q=.8" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/xml` withCharset `UTF-16`) should select(`text/xml`, `UTF-16`)
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*;q=0" test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/xml` withCharset `UTF-16`) should reject
      accept(`audio/ogg`) should reject
    }

    "Accept-Charset: UTF-16" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "manually created Accept-Charset: UTF-16" in testHeaders(headers.`Accept-Charset`(Vector(HttpCharsets.`UTF-16`.toRange))) { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept-Charset: UTF-16, UTF-8" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept-Charset: UTF-8;q=.2, UTF-16" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain`, `UTF-8`)
    }

    "Accept-Charset: ISO-8859-1;q=.2" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `ISO-8859-1`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept-Charset: latin1;q=.1, UTF-8;q=.2" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain`, `UTF-8`)
    }

    "Accept-Charset: *" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept-Charset: *;q=0" test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/plain` withCharset `UTF-16`) should reject
    }

    "Accept-Charset: us;q=0.1,*;q=0" test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `US-ASCII`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept: text/xml, text/html;q=.5" test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/xml`) should select(`text/xml`, `UTF-8`)
      accept(`text/html`) should select(`text/html`, `UTF-8`)
      accept(`text/html`, `text/xml`) should select(`text/xml`, `UTF-8`)
      accept(`text/xml`, `text/html`) should select(`text/xml`, `UTF-8`)
      accept(`text/plain`, `text/xml`) should select(`text/xml`, `UTF-8`)
      accept(`text/plain`, `text/html`) should select(`text/html`, `UTF-8`)
    }

    """Accept: text/html, text/plain;q=0.8, application/*;q=.5, *;q= .2
      |Accept-Charset: UTF-16""" test { accept ⇒
      accept(`text/plain`, `text/html`, `audio/ogg`) should select(`text/html`, `UTF-16`)
      accept(`text/plain`, `text/html` withCharset `UTF-8`, `audio/ogg`) should select(`text/plain`, `UTF-16`)
      accept(`audio/ogg`, `application/javascript`, `text/plain` withCharset `UTF-8`) should select(`application/javascript`, `UTF-16`)
      accept(`image/gif`, `application/javascript`) should select(`application/javascript`, `UTF-16`)
      accept(`image/gif`, `audio/ogg`) should select(`image/gif`)
    }
  }

  def testHeaders[U](headers: HttpHeader*)(body: ((ContentType*) ⇒ Option[ContentType]) ⇒ U): U = {
    val request = HttpRequest(headers = headers.toVector)
    body { contentTypes ⇒
      import scala.concurrent.ExecutionContext.Implicits.global

      // creates a pseudo marshaller for X, that applies for all the given content types
      trait X
      object X extends X
      implicit val marshallers: ToEntityMarshaller[X] =
        Marshaller.oneOf(contentTypes map {
          case ct @ ContentType(mt, Some(cs)) ⇒
            Marshaller.withFixedCharset(mt, cs)((s: X) ⇒ HttpEntity(ct, "The X"))
          case ContentType(mt, None) ⇒
            mt.encoding match {
              case Encoding.Open ⇒ Marshaller.withOpenCharset(mt)((s: X, cs) ⇒ HttpEntity(ContentType(mt, cs), "The X"))
              case _             ⇒ Marshaller.withOpenCharset(mt)((s: X, _) ⇒ HttpEntity(ContentType(mt, None), "The X"))
            }
        }: _*)

      Await.result(Marshal(X).toResponseFor(request)
        .fast.map(response ⇒ Some(response.entity.contentType))
        .fast.recover { case _: Marshal.UnacceptableResponseContentTypeException ⇒ None }, 1.second)
    }
  }

  def reject = equal(None)
  def select(mediaType: MediaType, charset: HttpCharset) = equal(Some(ContentType(mediaType, charset)))
  def select(mediaType: MediaType) = equal(Some(ContentType(mediaType, None)))

  implicit class AddStringToIn(example: String) {
    def test(body: ((ContentType*) ⇒ Option[ContentType]) ⇒ Unit): Unit = example in {
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
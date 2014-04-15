/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model

import org.scalatest.{ Matchers, FreeSpec }
import akka.http.model.parser.HeaderParser
import headers._
import MediaTypes._
import HttpCharsets._

class ContentNegotiationSpec extends FreeSpec with Matchers {

  "Content Negotiation should work properly for requests with header(s)" - {

    "(without headers)" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept: */*" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept: */*;q=.8" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept: text/*" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/xml` withCharset `UTF-16`) should select(`text/xml`, `UTF-16`)
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*;q=.8" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/xml` withCharset `UTF-16`) should select(`text/xml`, `UTF-16`)
      accept(`audio/ogg`) should reject
    }

    "Accept: text/*;q=0" in test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/xml` withCharset `UTF-16`) should reject
      accept(`audio/ogg`) should reject
    }

    "Accept-Charset: UTF-16" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept-Charset: UTF-16, UTF-8" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept-Charset: UTF-8;q=.2, UTF-16" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-16`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain`, `UTF-8`)
    }

    "Accept-Charset: UTF-8;q=.2" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `ISO-8859-1`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain`, `UTF-8`)
    }

    "Accept-Charset: latin1;q=.1, UTF-8;q=.2" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-8`) should select(`text/plain`, `UTF-8`)
    }

    "Accept-Charset: *" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `UTF-8`)
      accept(`text/plain` withCharset `UTF-16`) should select(`text/plain`, `UTF-16`)
    }

    "Accept-Charset: *;q=0" in test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/plain` withCharset `UTF-16`) should reject
    }

    "Accept-Charset: us;q=0.1,*;q=0" in test { accept ⇒
      accept(`text/plain`) should select(`text/plain`, `US-ASCII`)
      accept(`text/plain` withCharset `UTF-8`) should reject
    }

    "Accept: text/xml, text/html;q=.5" in test { accept ⇒
      accept(`text/plain`) should reject
      accept(`text/xml`) should select(`text/xml`, `UTF-8`)
      accept(`text/html`) should select(`text/html`, `UTF-8`)
      accept(`text/html`, `text/xml`) should select(`text/xml`, `UTF-8`)
      accept(`text/xml`, `text/html`) should select(`text/xml`, `UTF-8`)
      accept(`text/plain`, `text/xml`) should select(`text/xml`, `UTF-8`)
      accept(`text/plain`, `text/html`) should select(`text/html`, `UTF-8`)
    }

    """Accept: text/html, text/plain;q=0.8, application/*;q=.5, *;q= .2
       Accept-Charset: UTF-16""" in test { accept ⇒
      accept(`text/plain`, `text/html`, `audio/ogg`) should select(`text/html`, `UTF-16`)
      accept(`text/plain`, `text/html` withCharset `UTF-8`, `audio/ogg`) should select(`text/plain`, `UTF-16`)
      accept(`audio/ogg`, `application/javascript`, `text/plain` withCharset `UTF-8`) should select(`application/javascript`, `UTF-16`)
      accept(`image/gif`, `application/javascript`) should select(`application/javascript`, `UTF-16`)
      accept(`image/gif`, `audio/ogg`) should select(`image/gif`, `UTF-16`)
    }
  }

  def test[U](body: ((ContentType*) ⇒ Option[ContentType]) ⇒ U): String ⇒ U = { example ⇒
    val headers =
      if (example != "(without headers)") {
        example.split('\n').toList map { rawHeader ⇒
          val Array(name, value) = rawHeader.split(':')
          HeaderParser.parseHeader(RawHeader(name.trim, value.trim)) match {
            case Right(header) ⇒ header
            case Left(err)     ⇒ fail(err.formatPretty)
          }
        }
      } else Nil
    val request = HttpRequest(headers = headers)
    body(request.acceptableContentType)
  }

  def reject = equal(None)
  def select(mediaType: MediaType, charset: HttpCharset) = equal(Some(ContentType(mediaType, charset)))
}
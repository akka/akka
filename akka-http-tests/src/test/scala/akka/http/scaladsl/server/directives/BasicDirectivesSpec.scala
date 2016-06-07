/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.util.ByteString

class BasicDirectivesSpec extends RoutingSpec {

  "The `mapUnmatchedPath` directive" should {
    "map the unmatched path" in {
      Get("/abc") ~> {
        mapUnmatchedPath(_ / "def") {
          path("abc" / "def") { completeOk }
        }
      } ~> check { response shouldEqual Ok }
    }
  }

  "The `extract` directive" should {
    "extract from the RequestContext" in {
      Get("/abc") ~> {
        extract(_.request.method.value) {
          echoComplete
        }
      } ~> check { responseAs[String] shouldEqual "GET" }
    }
  }

  "The `extractDataBytes` directive" should {
    "extract stream of ByteString from the RequestContext" in {
      val dataBytes = Source.fromIterator(() ⇒ Iterator.range(1, 10).map(x ⇒ ByteString(x.toString)))
      Post("/abc", HttpEntity(ContentTypes.`text/plain(UTF-8)`, data = dataBytes)) ~> {
        extractDataBytes { data ⇒
          val sum = data.runFold(0) { (acc, i) ⇒ acc + i.utf8String.toInt }
          onSuccess(sum) { s ⇒
            complete(HttpResponse(entity = HttpEntity(s.toString)))
          }
        }
      } ~> check { responseAs[String] shouldEqual "45" }
    }
  }

  "The `extractRequestEntity` directive" should {
    "extract entity from the RequestContext" in {
      val httpEntity = HttpEntity(ContentTypes.`text/plain(UTF-8)`, "req")
      Post("/abc", httpEntity) ~> {
        extractRequestEntity { complete(_) }
      } ~> check { responseEntity shouldEqual httpEntity }
    }
  }
}
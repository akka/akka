/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.stream.scaladsl.Source
import akka.util.ByteString
import scala.concurrent.duration._
import java.util.concurrent.ThreadLocalRandom

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

  "The `extractMatchedPath` directive" should {
    "extract bar if /foo has been matched for /foo/bar" in {
      Get("/foo") ~> {
        pathPrefix("foo") {
          extractMatchedPath { matched ⇒
            complete(matched.toString)
          }
        }
      } ~> check { responseAs[String] shouldEqual "/foo" }
    }

    "extract bar with slash if /foo/ with slash has been matched for /foo/bar" in {
      Get("/foo/") ~> {
        pathPrefix("foo"/) {
          extractMatchedPath { matched ⇒
            complete(matched.toString)
          }
        }
      } ~> check { responseAs[String] shouldEqual "/foo/" }
    }

    "extract bar with slash if /foo/ with slash has been matched for /foo/bar if nested directives used" in {
      Get("/foo/bar/car") ~> {
        pathPrefix("foo") {
          pathPrefix("bar") {
            extractMatchedPath { matched ⇒
              complete(matched.toString)
            }
          }
        }
      } ~> check { responseAs[String] shouldEqual "/foo/bar" }
    }

    "extract all if fully matched" in {
      Get("/foo/bar") ~> {
        pathPrefix("foo") {
          pathPrefix("bar") {
            extractMatchedPath { matched ⇒
              complete(matched.toString)
            }
          }
        }
      } ~> check { responseAs[String] shouldEqual "/foo/bar" }
    }

    "extract nothing if root path" in {
      Get("/foo/bar") ~> {
        extractMatchedPath { matched ⇒
          complete(matched.toString)
        }
      } ~> check { responseAs[String] shouldEqual "" }
    }
  }

  "The `extractStrictEntity` directive" should {
    "change request to contain strict entity for inner routes" in {
      val chunks = () ⇒ List("Akka", "HTTP").map(HttpEntity.Chunk(_)).iterator
      val entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, Source.fromIterator(chunks))

      Post("/abc", entity) ~> {
        extractRequestEntity { before ⇒
          extractStrictEntity(200.millis) { _ ⇒
            extractRequestEntity { after ⇒
              complete(Seq(before, after).map(_.isInstanceOf[HttpEntity.Strict]).mkString(" => "))
            }
          }
        }
      } ~> check { responseAs[String] shouldEqual "false => true" }
    }

    "only consume data once when nested" in {
      val randomStream = Iterator.continually(ThreadLocalRandom.current.nextInt(0, 2).toString).take(100)
      val chunks = Source.fromIterator(() ⇒ randomStream.map(HttpEntity.Chunk(_)))
      val entity = HttpEntity.Chunked(ContentTypes.`text/plain(UTF-8)`, chunks)

      Post("/abc", entity) ~> {
        extractStrictEntity(200.millis) { outer ⇒
          extractStrictEntity(200.millis) { inner ⇒
            /* Check that the string representations of the outer and inner
             * random number sequences are identical. */
            complete(Seq(outer, inner).map(_.data.utf8String).distinct.size.toString)
          }
        }
      } ~> check { responseAs[String] shouldEqual "1" }
    }
  }
}

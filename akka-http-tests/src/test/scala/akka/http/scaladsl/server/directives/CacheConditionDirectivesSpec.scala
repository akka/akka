/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import StatusCodes._
import headers._

class CacheConditionDirectivesSpec extends RoutingSpec {

  "the `conditional` directive" should {
    val timestamp = DateTime.now - 2000
    val ifUnmodifiedSince = `If-Unmodified-Since`(timestamp)
    val ifModifiedSince = `If-Modified-Since`(timestamp)
    val tag = EntityTag("fresh")
    val responseHeaders = List(ETag(tag), `Last-Modified`(timestamp))

    def taggedAndTimestamped = conditional(tag, timestamp) { completeOk }
    def weak = conditional(tag.copy(weak = true), timestamp) { completeOk }

    "return OK for new resources" in {
      Get() ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
    }

    "return OK for non-matching resources" in {
      Get() ~> `If-None-Match`(EntityTag("old")) ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-None-Match`(EntityTag("old")) ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
    }

    "ignore If-Modified-Since if If-None-Match is defined" in {
      Get() ~> `If-None-Match`(tag) ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
      }
      Get() ~> `If-None-Match`(EntityTag("old")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
      }
    }

    "return PreconditionFailed for matched but unsafe resources" in {
      Put() ~> `If-None-Match`(tag) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual PreconditionFailed
        headers shouldEqual Nil
      }
    }

    "return NotModified for matching resources" in {
      Get() ~> `If-None-Match`.`*` ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> `If-Modified-Since`(timestamp + 1000) ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-None-Match`(tag.copy(weak = true)) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-None-Match`(tag, EntityTag("some"), EntityTag("other")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
    }

    "return NotModified when only one matching header is set" in {
      Get() ~> `If-None-Match`.`*` ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
      Get() ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (responseHeaders)
      }
    }

    "return NotModified for matching weak resources" in {
      val weakTag = tag.copy(weak = true)
      Get() ~> `If-None-Match`(tag) ~> weak ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (List(ETag(weakTag), `Last-Modified`(timestamp)))
      }
      Get() ~> `If-None-Match`(weakTag) ~> weak ~> check {
        status shouldEqual NotModified
        headers should contain theSameElementsAs (List(ETag(weakTag), `Last-Modified`(timestamp)))
      }
    }

    "return normally for matching If-Match/If-Unmodified" in {
      Put() ~> `If-Match`.`*` ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
      Put() ~> `If-Match`(tag) ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
      Put() ~> ifUnmodifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
        headers should contain theSameElementsAs (responseHeaders)
      }
    }

    "return PreconditionFailed for non-matching If-Match/If-Unmodified" in {
      Put() ~> `If-Match`(EntityTag("old")) ~> taggedAndTimestamped ~> check {
        status shouldEqual PreconditionFailed
        headers shouldEqual Nil
      }
      Put() ~> `If-Unmodified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status shouldEqual PreconditionFailed
        headers shouldEqual Nil
      }
    }

    "ignore If-Unmodified-Since if If-Match is defined" in {
      Put() ~> `If-Match`(tag) ~> `If-Unmodified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status shouldEqual OK
      }
      Put() ~> `If-Match`(EntityTag("old")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status shouldEqual PreconditionFailed
      }
    }

    "not filter out a `Range` header if `If-Range` does match the timestamp" in {
      Get() ~> `If-Range`(timestamp) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status shouldEqual OK
        responseAs[String] should startWith("Some")
      }
    }

    "filter out a `Range` header if `If-Range` doesn't match the timestamp" in {
      Get() ~> `If-Range`(timestamp - 1000) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status shouldEqual OK
        responseAs[String] shouldEqual "None"
      }
    }

    "not filter out a `Range` header if `If-Range` does match the ETag" in {
      Get() ~> `If-Range`(tag) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status shouldEqual OK
        responseAs[String] should startWith("Some")
      }
    }

    "filter out a `Range` header if `If-Range` doesn't match the ETag" in {
      Get() ~> `If-Range`(EntityTag("other")) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status shouldEqual OK
        responseAs[String] shouldEqual "None"
      }
    }
  }

}

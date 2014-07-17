/*
 * Copyright © 2011-2014 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import akka.http.model._
import StatusCodes._
import akka.http.routing
import headers._
import akka.http.util.DateTime

class CacheConditionDirectivesSpec extends RoutingSpec {

  "the `conditional` directive" should {
    val timestamp = DateTime.now - 2000
    val ifUnmodifiedSince = `If-Unmodified-Since`(timestamp)
    val ifModifiedSince = `If-Modified-Since`(timestamp)
    val tag = EntityTag("fresh")
    val responseHeaders = List(ETag(tag), `Last-Modified`(timestamp))

    def taggedAndTimestamped = conditional(tag, timestamp) { completeOk }
    def weak = conditional(tag.copy(weak = true), timestamp) { completeOk }

    "return OK for new resources" in pendingUntilFixed {
      Get() ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        routing.FIXME //headers should contain(allOf(responseHeaders))
      }
    }

    "return OK for non-matching resources" in pendingUntilFixed {
      Get() ~> `If-None-Match`(EntityTag("old")) ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        headers must contain(responseHeaders)
      }
      Get() ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        headers must contain(responseHeaders)
      }
      Get() ~> `If-None-Match`(EntityTag("old")) ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        headers must contain(responseHeaders)
      }
    }

    "ignore If-Modified-Since if If-None-Match is defined" in pendingUntilFixed {
      Get() ~> `If-None-Match`(tag) ~> `If-Modified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
      }
      Get() ~> `If-None-Match`(EntityTag("old")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual OK
      }
    }

    "return PreconditionFailed for matched but unsafe resources" in pendingUntilFixed {
      Put() ~> `If-None-Match`(tag) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual PreconditionFailed
        headers mustEqual Nil
      }
    }

    "return NotModified for matching resources" in pendingUntilFixed {
      Get() ~> `If-None-Match`.`*` ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> `If-Modified-Since`(timestamp + 1000) ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag.copy(weak = true)) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag, EntityTag("some"), EntityTag("other")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
    }

    "return NotModified when only one matching header is set" in pendingUntilFixed {
      Get() ~> `If-None-Match`.`*` ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
      Get() ~> `If-None-Match`(tag) ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
      Get() ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual NotModified
        headers must contain(responseHeaders)
      }
    }

    "return NotModified for matching weak resources" in pendingUntilFixed {
      val weakTag = tag.copy(weak = true)
      Get() ~> `If-None-Match`(tag) ~> weak ~> check {
        status mustEqual NotModified
        headers must contain(List(ETag(weakTag), `Last-Modified`(timestamp)))
      }
      Get() ~> `If-None-Match`(weakTag) ~> weak ~> check {
        status mustEqual NotModified
        headers must contain(List(ETag(weakTag), `Last-Modified`(timestamp)))
      }
    }

    "return normally for matching If-Match/If-Unmodified" in pendingUntilFixed {
      Put() ~> `If-Match`.`*` ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        headers must contain(responseHeaders)
      }
      Put() ~> `If-Match`(tag) ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        headers must contain(responseHeaders)
      }
      Put() ~> ifUnmodifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual OK
        headers must contain(responseHeaders)
      }
    }

    "return PreconditionFailed for non-matching If-Match/If-Unmodified" in pendingUntilFixed {
      Put() ~> `If-Match`(EntityTag("old")) ~> taggedAndTimestamped ~> check {
        status mustEqual PreconditionFailed
        headers mustEqual Nil
      }
      Put() ~> `If-Unmodified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status mustEqual PreconditionFailed
        headers mustEqual Nil
      }
    }

    "ignore If-Unmodified-Since if If-Match is defined" in pendingUntilFixed {
      Put() ~> `If-Match`(tag) ~> `If-Unmodified-Since`(timestamp - 1000) ~> taggedAndTimestamped ~> check {
        status mustEqual OK
      }
      Put() ~> `If-Match`(EntityTag("old")) ~> ifModifiedSince ~> taggedAndTimestamped ~> check {
        status mustEqual PreconditionFailed
      }
    }

    "not filter out a `Range` header if `If-Range` does match the timestamp" in pendingUntilFixed {
      Get() ~> `If-Range`(timestamp) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status mustEqual OK
        responseAs[String] must startWith("Some")
      }
    }

    "filter out a `Range` header if `If-Range` doesn't match the timestamp" in pendingUntilFixed {
      Get() ~> `If-Range`(timestamp - 1000) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status mustEqual OK
        responseAs[String] mustEqual "None"
      }
    }

    "not filter out a `Range` header if `If-Range` does match the ETag" in pendingUntilFixed {
      Get() ~> `If-Range`(tag) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status mustEqual OK
        responseAs[String] must startWith("Some")
      }
    }

    "filter out a `Range` header if `If-Range` doesn't match the ETag" in pendingUntilFixed {
      Get() ~> `If-Range`(EntityTag("other")) ~> Range(ByteRange(0, 10)) ~> {
        (conditional(tag, timestamp) & optionalHeaderValueByType[Range]()) { echoComplete }
      } ~> check {
        status mustEqual OK
        responseAs[String] mustEqual "None"
      }
    }
  }

}

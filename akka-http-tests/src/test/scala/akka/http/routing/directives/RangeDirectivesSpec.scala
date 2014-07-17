/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
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
import headers._
import StatusCodes._

import akka.http.routing._
import org.scalatest.{ Inside, Inspectors }

class RangeDirectivesSpec extends RoutingSpec with Inspectors with Inside {

  def bytes(length: Byte) = Array.tabulate[Byte](length)(_.toByte)

  "The `withRangeSupport` directive" should {
    // FIXME: figure out separate compilation problem when changing this line / file
    lazy val wrs = withRangeSupport(10, 1L)

    def completeWithRangedBytes(length: Byte) = wrs(complete(bytes(length)))

    "return an Accept-Ranges(bytes) header for GET requests" in pendingUntilFixed {
      Get() ~> { wrs { complete("any") } } ~> check {
        headers must contain(`Accept-Ranges`(RangeUnits.Bytes))
      }
    }

    "not return an Accept-Ranges(bytes) header for non-GET requests" in pendingUntilFixed {
      Put() ~> { wrs { complete("any") } } ~> check {
        headers must not contain `Accept-Ranges`(RangeUnits.Bytes)
      }
    }

    "return a Content-Range header for a ranged request with a single range" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange(0, 1))) ~> completeWithRangedBytes(10) ~> check {
        headers must contain(`Content-Range`(ContentRange(0, 1, 10)))
        status mustEqual PartialContent
        responseAs[Array[Byte]] mustEqual bytes(2)
      }
    }

    "return a partial response for a ranged request with a single range with undefined lastBytePosition" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange.fromOffset(5))) ~> completeWithRangedBytes(10) ~> check {
        responseAs[Array[Byte]] mustEqual Array[Byte](5, 6, 7, 8, 9)
      }
    }

    "return a partial response for a ranged request with a single suffix range" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange.suffix(1))) ~> completeWithRangedBytes(10) ~> check {
        responseAs[Array[Byte]] mustEqual Array[Byte](9)
      }
    }

    "return a partial response for a ranged request with a overlapping suffix range" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange.suffix(100))) ~> completeWithRangedBytes(10) ~> check {
        responseAs[Array[Byte]] mustEqual bytes(10)
      }
    }

    "be transparent to non-GET requests" in pendingUntilFixed {
      Post() ~> addHeader(Range(ByteRange(1, 2))) ~> completeWithRangedBytes(5) ~> check {
        responseAs[Array[Byte]] mustEqual bytes(5)
      }
    }

    "be transparent to non-200 responses" in pending /*{
      Get() ~> addHeader(Range(ByteRange(1, 2))) ~> HttpService.sealRoute(wrs(reject())) ~> check {
        status == NotFound
        headers must not(contain(like[HttpHeader] { case `Content-Range`(_, _) ⇒ ok }))
      }
    }*/

    "reject an unsatisfiable single range" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange(100, 200))) ~> completeWithRangedBytes(10) ~> check {
        rejection mustEqual UnsatisfiableRangeRejection(Seq(ByteRange(100, 200)), 10)
      }
    }

    "reject an unsatisfiable single suffix range with length 0" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange.suffix(0))) ~> completeWithRangedBytes(42) ~> check {
        rejection mustEqual UnsatisfiableRangeRejection(Seq(ByteRange.suffix(0)), 42)
      }
    }

    "return a mediaType of 'multipart/byteranges' for a ranged request with multiple ranges" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange(0, 10), ByteRange(0, 10))) ~> completeWithRangedBytes(10) ~> check {
        mediaType.withParams(Map.empty) mustEqual MediaTypes.`multipart/byteranges`
      }
    }

    "return a 'multipart/byteranges' for a ranged request with multiple coalesced ranges with preserved order" in pendingUntilFixed {
      Get() ~> addHeader(Range(ByteRange(5, 10), ByteRange(0, 1), ByteRange(1, 2))) ~> {
        wrs { complete("Some random and not super short entity.") }
      } ~> check {
        header[`Content-Range`] must be(None)
        inside(responseAs[MultipartByteRanges]) {
          case MultipartByteRanges(
            BodyPart(_: HttpEntity, _ +: `Content-Range`(RangeUnits.Bytes, ContentRange.Default(5, 10, Some(39))) +: _) +:
              BodyPart(_: HttpEntity, _ +: `Content-Range`(RangeUnits.Bytes, ContentRange.Default(0, 2, Some(39))) +: _) +:
              Seq()
            ) ⇒
        }
      }
    }

    "reject a request with too many requested ranges" in pendingUntilFixed {
      val ranges = (1 to 20).map(a ⇒ ByteRange.fromOffset(a))
      Get() ~> addHeader(Range(ranges)) ~> completeWithRangedBytes(100) ~> check {
        rejection mustEqual TooManyRangesRejection(10)
      }
    }
  }
}

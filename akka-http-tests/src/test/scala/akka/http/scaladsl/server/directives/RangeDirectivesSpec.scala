/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.concurrent.Await
import scala.concurrent.duration._
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._
import akka.stream.scaladsl.{ Sink, Source }
import akka.util.ByteString
import org.scalatest.{ Inside, Inspectors }

class RangeDirectivesSpec extends RoutingSpec with Inspectors with Inside {
  lazy val wrs =
    mapSettings(_.withRangeCountLimit(10).withRangeCoalescingThreshold(1L)) &
      withRangeSupport

  def bytes(length: Byte) = Array.tabulate[Byte](length)(_.toByte)

  "The `withRangeSupport` directive" should {
    def completeWithRangedBytes(length: Byte) = wrs(complete(bytes(length)))

    "return an Accept-Ranges(bytes) header for GET requests" in {
      Get() ~> { wrs { complete("any") } } ~> check {
        headers should contain(`Accept-Ranges`(RangeUnits.Bytes))
      }
    }

    "not return an Accept-Ranges(bytes) header for non-GET requests" in {
      Put() ~> { wrs { complete("any") } } ~> check {
        headers should not contain `Accept-Ranges`(RangeUnits.Bytes)
      }
    }

    "return a Content-Range header for a ranged request with a single range" in {
      Get() ~> addHeader(Range(ByteRange(0, 1))) ~> completeWithRangedBytes(10) ~> check {
        headers should contain(`Content-Range`(ContentRange(0, 1, 10)))
        status shouldEqual PartialContent
        responseAs[Array[Byte]] shouldEqual bytes(2)
      }
    }

    "return a partial response for a ranged request with a single range with undefined lastBytePosition" in {
      Get() ~> addHeader(Range(ByteRange.fromOffset(5))) ~> completeWithRangedBytes(10) ~> check {
        responseAs[Array[Byte]] shouldEqual Array[Byte](5, 6, 7, 8, 9)
      }
    }

    "return a partial response for a ranged request with a single suffix range" in {
      Get() ~> addHeader(Range(ByteRange.suffix(1))) ~> completeWithRangedBytes(10) ~> check {
        responseAs[Array[Byte]] shouldEqual Array[Byte](9)
      }
    }

    "return a partial response for a ranged request with a overlapping suffix range" in {
      Get() ~> addHeader(Range(ByteRange.suffix(100))) ~> completeWithRangedBytes(10) ~> check {
        responseAs[Array[Byte]] shouldEqual bytes(10)
      }
    }

    "be transparent to non-GET requests" in {
      Post() ~> addHeader(Range(ByteRange(1, 2))) ~> completeWithRangedBytes(5) ~> check {
        responseAs[Array[Byte]] shouldEqual bytes(5)
      }
    }

    "be transparent to non-200 responses" in {
      Get() ~> addHeader(Range(ByteRange(1, 2))) ~> Route.seal(wrs(reject())) ~> check {
        status == NotFound
        headers.exists { case `Content-Range`(_, _) ⇒ true; case _ ⇒ false } shouldEqual false
      }
    }

    "reject an unsatisfiable single range" in {
      Get() ~> addHeader(Range(ByteRange(100, 200))) ~> completeWithRangedBytes(10) ~> check {
        rejection shouldEqual UnsatisfiableRangeRejection(ByteRange(100, 200) :: Nil, 10)
      }
    }

    "reject an unsatisfiable single suffix range with length 0" in {
      Get() ~> addHeader(Range(ByteRange.suffix(0))) ~> completeWithRangedBytes(42) ~> check {
        rejection shouldEqual UnsatisfiableRangeRejection(ByteRange.suffix(0) :: Nil, 42)
      }
    }

    "return a mediaType of 'multipart/byteranges' for a ranged request with multiple ranges" in {
      Get() ~> addHeader(Range(ByteRange(0, 10), ByteRange(0, 10))) ~> completeWithRangedBytes(10) ~> check {
        mediaType.withParams(Map.empty) shouldEqual MediaTypes.`multipart/byteranges`
      }
    }

    "return a 'multipart/byteranges' for a ranged request with multiple coalesced ranges and expect ranges in ascending order" in {
      Get() ~> addHeader(Range(ByteRange(5, 10), ByteRange(0, 1), ByteRange(1, 2))) ~> {
        wrs { complete("Some random and not super short entity.") }
      } ~> check {
        header[`Content-Range`] should be(None)
        val parts = Await.result(responseAs[Multipart.ByteRanges].parts.limit(1000).runWith(Sink.seq), 1.second)
        parts.size shouldEqual 2
        inside(parts(0)) {
          case Multipart.ByteRanges.BodyPart(range, entity, unit, headers) ⇒
            range shouldEqual ContentRange.Default(0, 2, Some(39))
            unit shouldEqual RangeUnits.Bytes
            Await.result(entity.dataBytes.utf8String, 100.millis) shouldEqual "Som"
        }
        inside(parts(1)) {
          case Multipart.ByteRanges.BodyPart(range, entity, unit, headers) ⇒
            range shouldEqual ContentRange.Default(5, 10, Some(39))
            unit shouldEqual RangeUnits.Bytes
            Await.result(entity.dataBytes.utf8String, 100.millis) shouldEqual "random"
        }
      }
    }

    "return a 'multipart/byteranges' for a ranged request with multiple ranges if entity data source isn't reusable" in {
      val content = "Some random and not super short entity."
      def entityData() = StreamUtils.oneTimeSource(Source.single(ByteString(content)))

      Get() ~> addHeader(Range(ByteRange(5, 10), ByteRange(0, 1), ByteRange(1, 2))) ~> {
        wrs { complete(HttpEntity.Default(ContentTypes.`text/plain(UTF-8)`, content.length, entityData())) }
      } ~> check {
        header[`Content-Range`] should be(None)
        val parts = Await.result(responseAs[Multipart.ByteRanges].parts.limit(1000).runWith(Sink.seq), 1.second)
        parts.size shouldEqual 2
      }
    }

    "reject a request with too many requested ranges" in {
      val ranges = (1 to 20).map(a ⇒ ByteRange.fromOffset(a))
      Get() ~> addHeader(Range(ranges)) ~> completeWithRangedBytes(100) ~> check {
        rejection shouldEqual TooManyRangesRejection(10)
      }
    }
  }
}

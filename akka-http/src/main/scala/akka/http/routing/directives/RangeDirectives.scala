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

package akka.http.routing
package directives

import akka.stream.FlowMaterializer

import scala.collection.immutable

import akka.http.routing.util.StreamUtils._

import akka.shapeless.HNil
import akka.http.marshalling.ToEntityMarshaller
import akka.http.model._
import StatusCodes._
import headers._

import scala.concurrent.ExecutionContext

trait RangeDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Answers GET requests with an `Accept-Ranges: bytes` header and converts HttpResponses coming back from its inner
   * route into partial responses if the initial request contained a valid `Range` request header. The requested
   * byte-ranges may be coalesced.
   * This directive is transparent to non-GET requests
   * Rejects requests with unsatisfiable ranges `UnsatisfiableRangeRejection`.
   * Rejects requests with too many expected ranges.
   *
   * Note: if you want to combine this directive with `conditional(...)` you need to put
   * it on the *inside* of the `conditional(...)` directive, i.e. `conditional(...)` must be
   * on a higher level in your route structure in order to function correctly.
   *
   * @see https://tools.ietf.org/html/draft-ietf-httpbis-p5-range/
   */
  def withRangeSupport(m: RangeDirectives.WithRangeSupportMagnet): Directive0 = {
    import m._

    class IndexRange(val start: Long, val end: Long) {
      def length = end - start
      def apply(entity: HttpEntity) = entity.sliceData(start, end)
      def distance(other: IndexRange) = mergedEnd(other) - mergedStart(other) - (length + other.length)
      def mergeWith(other: IndexRange) = new IndexRange(mergedStart(other), mergedEnd(other))
      def contentRangeHeader(entityLength: Long) = `Content-Range`(ContentRange(start, end - 1, entityLength))
      private def mergedStart(other: IndexRange) = math.min(start, other.start)
      private def mergedEnd(other: IndexRange) = math.max(end, other.end)
    }

    def indexRange(entityLength: Long)(range: ByteRange): IndexRange =
      range match {
        case ByteRange.Slice(start, end)    ⇒ new IndexRange(start, math.min(end + 1, entityLength))
        case ByteRange.FromOffset(first)    ⇒ new IndexRange(first, entityLength)
        case ByteRange.Suffix(suffixLength) ⇒ new IndexRange(math.max(0, entityLength - suffixLength), entityLength)
      }

    /**
     * When multiple ranges are requested, a server may coalesce any of the ranges that overlap or that are separated
     * by a gap that is smaller than the overhead of sending multiple parts, regardless of the order in which the
     * corresponding byte-range-spec appeared in the received Range header field. Since the typical overhead between
     * parts of a multipart/byteranges payload is around 80 bytes, depending on the selected representation's
     * media type and the chosen boundary parameter length, it can be less efficient to transfer many small
     * disjoint parts than it is to transfer the entire selected representation.
     */
    def coalesceRanges(iRanges: Seq[IndexRange]): Seq[IndexRange] =
      iRanges.foldLeft(Seq.empty[IndexRange]) { (acc, iRange) ⇒
        val (mergeCandidates, otherCandidates) = acc.partition(_.distance(iRange) <= rangeCoalescingThreshold)
        val merged = mergeCandidates.foldLeft(iRange)(_ mergeWith _)
        otherCandidates :+ merged
      }

    def multipartRanges(ranges: Seq[ByteRange], entity: HttpEntity.Default): MultipartByteRanges = FIXME /*{
      val iRanges = ranges.map(indexRange(entity.contentLength))
      val bodyParts = coalesceRanges(iRanges).map(ir ⇒ BodyPart(ir(entity), Seq(ir.contentRangeHeader(entity))))
      MultipartByteRanges(bodyParts)
    }*/

    def rangeResponse(range: ByteRange, entity: HttpEntity, length: Long, headers: immutable.Seq[HttpHeader]) = {
      val aiRange = indexRange(length)(range)
      HttpResponse(PartialContent, aiRange.contentRangeHeader(length) +: headers, aiRange(entity))
    }

    def satisfiable(entityLength: Long)(range: ByteRange): Boolean =
      range match {
        case ByteRange.Slice(firstPos, _)   ⇒ firstPos < entityLength
        case ByteRange.FromOffset(firstPos) ⇒ firstPos < entityLength
        case ByteRange.Suffix(length)       ⇒ length > 0
      }
    def entityLength(entity: HttpEntity): Option[Long] = entity match {
      case HttpEntity.Strict(_, data)       ⇒ Some(data.length)
      case HttpEntity.Default(_, length, _) ⇒ Some(length)
      case _                                ⇒ None
    }

    def applyRanges(ranges: Seq[ByteRange]): Directive0 =
      mapRequestContext { ctx ⇒
        ctx.withRouteResponseHandling {
          case CompleteWith(HttpResponse(OK, headers, entity, protocol)) ⇒
            entityLength(entity) match {
              case Some(length) ⇒
                ranges.filter(satisfiable(length)) match {
                  case Nil                   ⇒ ctx.reject(UnsatisfiableRangeRejection(ranges, length))
                  case Seq(satisfiableRange) ⇒ ctx.complete(rangeResponse(satisfiableRange, entity, length, headers))
                  case satisfiableRanges ⇒
                    // multipart marshaller is still missing
                    // ctx.complete(PartialContent, headers, multipartRanges(satisfiableRanges, entity))
                    FIXME
                }
              case None ⇒
                // Ranges not supported for Chunked or CloseDelimited responses
                ctx.reject(UnsatisfiableRangeRejection(ranges, -1)) // FIXME: provide better error
            }
        }
      }

    def rangeHeaderOfGetRequests(ctx: RequestContext): Option[Range] =
      if (ctx.request.method == HttpMethods.GET) ctx.request.header[Range] else None

    extract(rangeHeaderOfGetRequests).flatMap[HNil] {
      case Some(Range(RangeUnits.Bytes, ranges)) ⇒
        if (ranges.size <= rangeCountLimit) applyRanges(ranges) & RangeDirectives.respondWithAcceptByteRangesHeader
        else reject(TooManyRangesRejection(rangeCountLimit))
      case _ ⇒ MethodDirectives.get & RangeDirectives.respondWithAcceptByteRangesHeader | pass
    }
  }
}

object RangeDirectives extends RangeDirectives {
  private val respondWithAcceptByteRangesHeader: Directive0 =
    RespondWithDirectives.respondWithHeader(`Accept-Ranges`(RangeUnits.Bytes))

  class WithRangeSupportMagnet(val rangeCountLimit: Int, val rangeCoalescingThreshold: Long)(implicit val marshaller: ToEntityMarshaller[MultipartByteRanges], val executionContext: ExecutionContext, val materializer: FlowMaterializer)
  object WithRangeSupportMagnet {
    implicit def fromSettings(u: Unit)(implicit settings: RoutingSettings, m: ToEntityMarshaller[MultipartByteRanges], ec: ExecutionContext, materializer: FlowMaterializer) =
      new WithRangeSupportMagnet(settings.rangeCountLimit, settings.rangeCoalescingThreshold)
    implicit def fromCountLimitAndCoalescingThreshold(t: (Int, Long))(implicit m: ToEntityMarshaller[MultipartByteRanges], ec: ExecutionContext, materializer: FlowMaterializer) =
      new WithRangeSupportMagnet(t._1, t._2)
  }
}


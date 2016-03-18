/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.RouteResult.Complete
import akka.http.impl.util._
import akka.stream.scaladsl._
import scala.collection.immutable
import akka.util.ByteString
import akka.stream.SourceShape
import akka.stream.OverflowStrategy

trait RangeDirectives {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.RouteDirectives._

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
   * @see [[https://tools.ietf.org/html/rfc7233]]
   */
  def withRangeSupport: Directive0 =
    extractRequestContext.flatMap { ctx ⇒
      val settings = ctx.settings
      implicit val log = ctx.log
      import settings.{ rangeCountLimit, rangeCoalescingThreshold }

      class IndexRange(val start: Long, val end: Long) {
        def length = end - start
        def apply(entity: UniversalEntity): UniversalEntity = entity.transformDataBytes(length, StreamUtils.sliceBytesTransformer(start, length))
        def distance(other: IndexRange) = mergedEnd(other) - mergedStart(other) - (length + other.length)
        def mergeWith(other: IndexRange) = new IndexRange(mergedStart(other), mergedEnd(other))
        def contentRange(entityLength: Long) = ContentRange(start, end - 1, entityLength)
        private def mergedStart(other: IndexRange) = math.min(start, other.start)
        private def mergedEnd(other: IndexRange) = math.max(end, other.end)
      }

      def indexRange(entityLength: Long)(range: ByteRange): IndexRange =
        range match {
          case ByteRange.Slice(start, end)    ⇒ new IndexRange(start, math.min(end + 1, entityLength))
          case ByteRange.FromOffset(first)    ⇒ new IndexRange(first, entityLength)
          case ByteRange.Suffix(suffixLength) ⇒ new IndexRange(math.max(0, entityLength - suffixLength), entityLength)
        }

      // See comment of the `range-coalescing-threshold` setting in `reference.conf` for the rationale of this behavior.
      def coalesceRanges(iRanges: Seq[IndexRange]): Seq[IndexRange] =
        iRanges.foldLeft(Seq.empty[IndexRange]) { (acc, iRange) ⇒
          val (mergeCandidates, otherCandidates) = acc.partition(_.distance(iRange) <= rangeCoalescingThreshold)
          val merged = mergeCandidates.foldLeft(iRange)(_ mergeWith _)
          otherCandidates :+ merged
        }

      def multipartRanges(ranges: Seq[ByteRange], entity: UniversalEntity): Multipart.ByteRanges = {
        val length = entity.contentLength
        val iRanges: Seq[IndexRange] = ranges.map(indexRange(length))

        // It's only possible to run once over the input entity data stream because it's not known if the
        // source is reusable.
        // Therefore, ranges need to be sorted to prevent that some selected ranges already start to accumulate data
        // but cannot be sent out because another range is blocking the queue.
        val coalescedRanges = coalesceRanges(iRanges).sortBy(_.start)
        val source = coalescedRanges.size match {
          case 0 ⇒ Source.empty
          case 1 ⇒
            val range = coalescedRanges.head
            val flow = StreamUtils.sliceBytesTransformer(range.start, range.length)
            val bytes = entity.dataBytes.via(flow)
            val part = Multipart.ByteRanges.BodyPart(range.contentRange(length), HttpEntity(entity.contentType, range.length, bytes))
            Source.single(part)
          case n ⇒
            Source fromGraph GraphDSL.create() { implicit b ⇒
              import GraphDSL.Implicits._
              val bcast = b.add(Broadcast[ByteString](n))
              val merge = b.add(Concat[Multipart.ByteRanges.BodyPart](n))
              for (range ← coalescedRanges) {
                val flow = StreamUtils.sliceBytesTransformer(range.start, range.length)
                bcast ~> flow.buffer(16, OverflowStrategy.backpressure).prefixAndTail(0).map {
                  case (_, bytes) ⇒
                    Multipart.ByteRanges.BodyPart(range.contentRange(length), HttpEntity(entity.contentType, range.length, bytes))
                } ~> merge
              }
              entity.dataBytes ~> bcast
              SourceShape(merge.out)
            }
        }
        Multipart.ByteRanges(source)
      }

      def rangeResponse(range: ByteRange, entity: UniversalEntity, length: Long, headers: immutable.Seq[HttpHeader]) = {
        val aiRange = indexRange(length)(range)
        HttpResponse(PartialContent, `Content-Range`(aiRange.contentRange(length)) +: headers, aiRange(entity))
      }

      def satisfiable(entityLength: Long)(range: ByteRange): Boolean =
        range match {
          case ByteRange.Slice(firstPos, _)   ⇒ firstPos < entityLength
          case ByteRange.FromOffset(firstPos) ⇒ firstPos < entityLength
          case ByteRange.Suffix(length)       ⇒ length > 0
        }
      def universal(entity: HttpEntity): Option[UniversalEntity] = entity match {
        case u: UniversalEntity ⇒ Some(u)
        case _                  ⇒ None
      }

      def applyRanges(ranges: immutable.Seq[ByteRange]): Directive0 =
        extractRequestContext.flatMap { ctx ⇒
          mapRouteResultWithPF {
            case Complete(HttpResponse(OK, headers, entity, protocol)) ⇒
              universal(entity) match {
                case Some(entity) ⇒
                  val length = entity.contentLength
                  ranges.filter(satisfiable(length)) match {
                    case Nil                   ⇒ ctx.reject(UnsatisfiableRangeRejection(ranges, length))
                    case Seq(satisfiableRange) ⇒ ctx.complete(rangeResponse(satisfiableRange, entity, length, headers))
                    case satisfiableRanges ⇒
                      ctx.complete((PartialContent, headers, multipartRanges(satisfiableRanges, entity)))
                  }
                case None ⇒
                  // Ranges not supported for Chunked or CloseDelimited responses
                  ctx.reject(UnsatisfiableRangeRejection(ranges, -1)) // FIXME: provide better error
              }
          }
        }

      def rangeHeaderOfGetRequests(ctx: RequestContext): Option[Range] =
        if (ctx.request.method == HttpMethods.GET) ctx.request.header[Range] else None

      extract(rangeHeaderOfGetRequests).flatMap {
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
}

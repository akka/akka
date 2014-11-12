/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.concurrent.{ ExecutionContext, Future }
import akka.http.util.FastFuture
import akka.http.model.HttpCharsets._
import akka.http.model._
import FastFuture._

object Marshal {
  def apply[T](value: T): Marshal[T] = new Marshal(value)

  case class UnacceptableResponseContentTypeException(supported: Set[ContentType]) extends RuntimeException

  private class MarshallingWeight(val weight: Float, val marshal: () ⇒ HttpResponse)
}

class Marshal[A](val value: A) {
  /**
   * Marshals `value` using the first available [[Marshalling]] for `A` and `B` provided by the given [[Marshaller]].
   * If the marshalling is flexible with regard to the used charset `UTF-8` is chosen.
   */
  def to[B](implicit m: Marshaller[A, B], ec: ExecutionContext): Future[B] =
    m(value).fast.map {
      _.head match {
        case Marshalling.WithFixedCharset(_, _, marshal) ⇒ marshal()
        case Marshalling.WithOpenCharset(_, marshal)     ⇒ marshal(HttpCharsets.`UTF-8`)
        case Marshalling.Opaque(marshal)                 ⇒ marshal()
      }
    }

  /**
   * Marshals `value` to an `HttpResponse` for the given `HttpRequest` with full content-negotiation.
   */
  def toResponseFor(request: HttpRequest)(implicit m: ToResponseMarshaller[A], ec: ExecutionContext): Future[HttpResponse] = {
    import akka.http.marshalling.Marshal._
    val mediaRanges = request.acceptedMediaRanges // cache for performance
    val charsetRanges = request.acceptedCharsetRanges // cache for performance
    def qValueMT(mediaType: MediaType) = request.qValueForMediaType(mediaType, mediaRanges)
    def qValueCS(charset: HttpCharset) = request.qValueForCharset(charset, charsetRanges)

    m(value).fast.map { marshallings ⇒
      def weight(mt: MediaType, cs: HttpCharset): Float = math.min(qValueMT(mt), qValueCS(cs))
      val defaultMarshallingWeight: MarshallingWeight =
        new MarshallingWeight(0f, { () ⇒
          val supportedContentTypes = marshallings collect {
            case Marshalling.WithFixedCharset(mt, cs, _) ⇒ ContentType(mt, cs)
            case Marshalling.WithOpenCharset(mt, _)      ⇒ ContentType(mt)
          }
          throw UnacceptableResponseContentTypeException(supportedContentTypes.toSet)
        })
      val best = marshallings.foldLeft(defaultMarshallingWeight) {
        case (acc, Marshalling.WithFixedCharset(mt, cs, marshal)) ⇒
          val w = weight(mt, cs)
          if (w > acc.weight) new MarshallingWeight(w, marshal) else acc

        case (acc, Marshalling.WithOpenCharset(mt, marshal)) ⇒
          def withCharset(cs: HttpCharset) = {
            val w = weight(mt, cs)
            if (w > acc.weight) new MarshallingWeight(w, () ⇒ marshal(cs)) else acc
          }
          // logic for choosing the charset adapted from http://tools.ietf.org/html/rfc7231#section-5.3.3
          if (qValueCS(`UTF-8`) == 1f) withCharset(`UTF-8`) // prefer UTF-8 if fully accepted
          else charsetRanges match { // ranges are sorted by descending q-value,
            case (HttpCharsetRange.One(cs, qValue)) :: _ ⇒ // so we only need to look at the first one
              if (qValue == 1f) withCharset(cs) // if the client has high preference for this charset, pick it
              else if (qValue > 0f) withCharset(cs) // ok, simply choose the first one if the client doesn't reject it
              else acc
            case _ ⇒ acc
          }

        case (acc, Marshalling.Opaque(marshal)) ⇒
          if (acc.weight == 0f) new MarshallingWeight(Float.MinPositiveValue, marshal) else acc
      }
      best.marshal()
    }
  }
}

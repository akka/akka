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
      val defaultMarshallingWeight = new MarshallingWeight(0f, { () ⇒
        val supportedContentTypes = marshallings collect {
          case Marshalling.WithFixedCharset(mt, cs, _) ⇒ ContentType(mt, cs)
          case Marshalling.WithOpenCharset(mt, _)      ⇒ ContentType(mt)
        }
        throw UnacceptableResponseContentTypeException(supportedContentTypes.toSet)
      })
      def choose(acc: MarshallingWeight, mt: MediaType, cs: HttpCharset, marshal: () ⇒ HttpResponse) = {
        val weight = math.min(qValueMT(mt), qValueCS(cs))
        if (weight > acc.weight) new MarshallingWeight(weight, marshal) else acc
      }
      val best = marshallings.foldLeft(defaultMarshallingWeight) {
        case (acc, Marshalling.WithFixedCharset(mt, cs, marshal)) ⇒
          choose(acc, mt, cs, marshal)
        case (acc, Marshalling.WithOpenCharset(mt, marshal)) ⇒
          def withCharset(cs: HttpCharset) = choose(acc, mt, cs, () ⇒ marshal(cs))
          // logic for choosing the charset adapted from http://tools.ietf.org/html/rfc7231#section-5.3.3
          if (qValueCS(`UTF-8`) == 1f) withCharset(`UTF-8`) // prefer UTF-8 if fully accepted
          else charsetRanges match {
            // pick the charset which the highest q-value (head of charsetRanges) if it isn't explicitly rejected
            case (HttpCharsetRange.One(cs, qValue)) :: _ if qValue > 0f ⇒ withCharset(cs)
            case _ ⇒ acc
          }

        case (acc, Marshalling.Opaque(marshal)) ⇒
          if (acc.weight == 0f) new MarshallingWeight(Float.MinPositiveValue, marshal) else acc
      }
      best.marshal()
    }
  }
}

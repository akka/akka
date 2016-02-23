/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import akka.stream.impl.ConstantFun

import scala.collection.immutable
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._

trait PredefinedToResponseMarshallers extends LowPriorityToResponseMarshallerImplicits {

  private type TRM[T] = ToResponseMarshaller[T] // brevity alias

  def fromToEntityMarshaller[T](status: StatusCode = StatusCodes.OK,
                                headers: immutable.Seq[HttpHeader] = Nil)(
                                  implicit m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    fromStatusCodeAndHeadersAndValue compose (t ⇒ (status, headers, t))

  implicit val fromResponse: TRM[HttpResponse] = Marshaller.opaque(ConstantFun.scalaIdentityFunction)

  implicit val fromStatusCode: TRM[StatusCode] =
    Marshaller.withOpenCharset(`text/plain`) { (status, charset) ⇒
      HttpResponse(status, entity = HttpEntity(ContentType(`text/plain`, charset), status.defaultMessage))
    }

  implicit def fromStatusCodeAndValue[S, T](implicit sConv: S ⇒ StatusCode, mt: ToEntityMarshaller[T]): TRM[(S, T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, value) ⇒ (sConv(status), Nil, value) }

  implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit sConv: S ⇒ StatusCode,
                                                             mt: ToEntityMarshaller[T]): TRM[(S, immutable.Seq[HttpHeader], T)] =
    fromStatusCodeAndHeadersAndValue[T] compose { case (status, headers, value) ⇒ (sConv(status), headers, value) }

  implicit def fromStatusCodeAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(StatusCode, immutable.Seq[HttpHeader], T)] =
    Marshaller(implicit ec ⇒ {
      case (status, headers, value) ⇒ mt(value).fast map (_ map (_ map (HttpResponse(status, headers, _))))
    })
}

trait LowPriorityToResponseMarshallerImplicits {
  implicit def liftMarshallerConversion[T](m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    liftMarshaller(m)
  implicit def liftMarshaller[T](implicit m: ToEntityMarshaller[T]): ToResponseMarshaller[T] =
    PredefinedToResponseMarshallers.fromToEntityMarshaller()
}

object PredefinedToResponseMarshallers extends PredefinedToResponseMarshallers

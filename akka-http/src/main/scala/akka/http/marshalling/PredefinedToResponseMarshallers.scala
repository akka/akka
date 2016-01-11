/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import akka.http.model.MediaTypes._
import akka.http.model._

trait PredefinedToResponseMarshallers extends LowPriorityToResponseMarshallerImplicits {

  private type TRM[T] = ToResponseMarshaller[T] // brevity alias

  def fromToEntityMarshaller[T](status: StatusCode = StatusCodes.OK, headers: immutable.Seq[HttpHeader] = Nil)(implicit m: ToEntityMarshaller[T], ec: ExecutionContext): ToResponseMarshaller[T] =
    fromStatusCodeAndHeadersAndValue.compose(t ⇒ (status, headers, t))

  implicit val fromResponse: TRM[HttpResponse] = Marshaller.opaque(identity)

  implicit val fromStatusCode: TRM[StatusCode] =
    Marshaller.withOpenCharset(`text/plain`) { (status, charset) ⇒
      HttpResponse(status, entity = HttpEntity(ContentType(`text/plain`, charset), status.defaultMessage))
    }

  implicit def fromStatusCodeAndValue[S, T](implicit sConv: S ⇒ StatusCode, mt: ToEntityMarshaller[T],
                                            ec: ExecutionContext): TRM[(S, T)] =
    fromStatusCodeAndHeadersAndValue[T].compose { case (status, value) ⇒ (sConv(status), Nil, value) }

  implicit def fromStatusCodeConvertibleAndHeadersAndT[S, T](implicit sConv: S ⇒ StatusCode, mt: ToEntityMarshaller[T],
                                                             ec: ExecutionContext): TRM[(S, immutable.Seq[HttpHeader], T)] =
    fromStatusCodeAndHeadersAndValue[T].compose { case (status, headers, value) ⇒ (sConv(status), headers, value) }

  implicit def fromStatusCodeAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T],
                                                   ec: ExecutionContext): TRM[(StatusCode, immutable.Seq[HttpHeader], T)] =
    Marshaller { case (status, headers, value) ⇒ mt(value).map(_ map (HttpResponse(status, headers, _))) }
}

trait LowPriorityToResponseMarshallerImplicits {
  implicit def liftMarshallerConversion[T](m: ToEntityMarshaller[T])(implicit ec: ExecutionContext): ToResponseMarshaller[T] =
    liftMarshaller(m, ec)
  implicit def liftMarshaller[T](implicit m: ToEntityMarshaller[T], ec: ExecutionContext): ToResponseMarshaller[T] =
    PredefinedToResponseMarshallers.fromToEntityMarshaller()
}

object PredefinedToResponseMarshallers extends PredefinedToResponseMarshallers


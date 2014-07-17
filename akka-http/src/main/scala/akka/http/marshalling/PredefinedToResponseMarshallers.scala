/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import akka.http.model.MediaTypes._
import akka.http.model._

import scala.collection.immutable
import scala.concurrent.ExecutionContext

trait PredefinedToResponseMarshallers {

  private type TRM[T] = ToResponseMarshaller[T] // brevity alias

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

object PredefinedToResponseMarshallers extends PredefinedToResponseMarshallers


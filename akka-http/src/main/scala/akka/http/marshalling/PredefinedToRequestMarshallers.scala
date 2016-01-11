/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import akka.http.util.FastFuture
import akka.http.model._
import FastFuture._

trait PredefinedToRequestMarshallers {
  private type TRM[T] = ToRequestMarshaller[T] // brevity alias

  implicit val fromRequest: TRM[HttpRequest] = Marshaller.opaque(identity)

  implicit def fromUri(implicit ec: ExecutionContext): TRM[Uri] =
    Marshaller { uri ⇒ FastFuture.successful(Marshalling.Opaque(() ⇒ HttpRequest(uri = uri))) }

  implicit def fromMethodAndUriAndValue[S, T](implicit mt: ToEntityMarshaller[T],
                                              ec: ExecutionContext): TRM[(HttpMethod, Uri, T)] =
    fromMethodAndUriAndHeadersAndValue[T].compose { case (m, u, v) ⇒ (m, u, Nil, v) }

  implicit def fromMethodAndUriAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T],
                                                     ec: ExecutionContext): TRM[(HttpMethod, Uri, immutable.Seq[HttpHeader], T)] =
    Marshaller { case (m, u, h, v) ⇒ mt(v).fast.map(_ map (HttpRequest(m, u, h, _))) }
}

object PredefinedToRequestMarshallers extends PredefinedToRequestMarshallers


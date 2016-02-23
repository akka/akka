/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.collection.immutable
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture._

trait PredefinedToRequestMarshallers {
  private type TRM[T] = ToRequestMarshaller[T] // brevity alias

  implicit val fromRequest: TRM[HttpRequest] = Marshaller.opaque(conforms)

  implicit def fromUri: TRM[Uri] =
    Marshaller strict { uri ⇒ Marshalling.Opaque(() ⇒ HttpRequest(uri = uri)) }

  implicit def fromMethodAndUriAndValue[S, T](implicit mt: ToEntityMarshaller[T]): TRM[(HttpMethod, Uri, T)] =
    fromMethodAndUriAndHeadersAndValue[T] compose { case (m, u, v) ⇒ (m, u, Nil, v) }

  implicit def fromMethodAndUriAndHeadersAndValue[T](implicit mt: ToEntityMarshaller[T]): TRM[(HttpMethod, Uri, immutable.Seq[HttpHeader], T)] =
    Marshaller(implicit ec ⇒ {
      case (m, u, h, v) ⇒ mt(v).fast map (_ map (_ map (HttpRequest(m, u, h, _))))
    })
}

object PredefinedToRequestMarshallers extends PredefinedToRequestMarshallers

/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.collection.immutable
import akka.http.model._

package object marshalling {
  type ToEntityMarshaller[T] = Marshaller[T, HttpEntity.Regular]
  type ToHeadersAndEntityMarshaller[T] = Marshaller[T, (immutable.Seq[HttpHeader], HttpEntity.Regular)]
  type ToResponseMarshaller[T] = Marshaller[T, HttpResponse]
  type ToRequestMarshaller[T] = Marshaller[T, HttpRequest]

  type ToEntityMarshallers[T] = Marshallers[T, HttpEntity.Regular]
  type ToResponseMarshallers[T] = Marshallers[T, HttpResponse]
  type ToRequestMarshallers[T] = Marshallers[T, HttpRequest]
}

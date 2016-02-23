/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import scala.collection.immutable
import akka.http.scaladsl.model._

package object marshalling {
  //# marshaller-aliases
  type ToEntityMarshaller[T] = Marshaller[T, MessageEntity]
  type ToHeadersAndEntityMarshaller[T] = Marshaller[T, (immutable.Seq[HttpHeader], MessageEntity)]
  type ToResponseMarshaller[T] = Marshaller[T, HttpResponse]
  type ToRequestMarshaller[T] = Marshaller[T, HttpRequest]
  //#
}

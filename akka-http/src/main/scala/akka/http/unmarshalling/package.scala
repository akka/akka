/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import scala.concurrent.Future
import akka.http.model._

package object unmarshalling {
  type FromEntityUnmarshaller[T] = Unmarshaller[HttpEntity, T]
  type FromMessageUnmarshaller[T] = Unmarshaller[HttpMessage, T]
  type FromResponseUnmarshaller[T] = Unmarshaller[HttpResponse, T]
  type FromRequestUnmarshaller[T] = Unmarshaller[HttpRequest, T]

  // FIXME: these are stubs until we have the real stuff (TM)
  type Deserialized[T] = Future[T]
  type FromStringDeserializer[T] = Deserializer[String, T]
  type FromStringOptionDeserializer[T] = Deserializer[Option[String], T]
}

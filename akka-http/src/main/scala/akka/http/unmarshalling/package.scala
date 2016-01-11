/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model._

package object unmarshalling {
  type FromEntityUnmarshaller[T] = Unmarshaller[HttpEntity, T]
  type FromMessageUnmarshaller[T] = Unmarshaller[HttpMessage, T]
  type FromResponseUnmarshaller[T] = Unmarshaller[HttpResponse, T]
  type FromRequestUnmarshaller[T] = Unmarshaller[HttpRequest, T]
}

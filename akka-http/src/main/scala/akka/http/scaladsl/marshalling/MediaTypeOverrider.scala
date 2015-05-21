/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshalling

import scala.collection.immutable
import akka.http.scaladsl.model._

sealed trait MediaTypeOverrider[T] {
  def apply(value: T, mediaType: MediaType): T
}
object MediaTypeOverrider {
  implicit def forEntity[T <: HttpEntity]: MediaTypeOverrider[T] = new MediaTypeOverrider[T] {
    def apply(value: T, mediaType: MediaType) =
      value.withContentType(value.contentType withMediaType mediaType).asInstanceOf[T] // can't be expressed in types
  }
  implicit def forHeadersAndEntity[T <: HttpEntity] = new MediaTypeOverrider[(immutable.Seq[HttpHeader], T)] {
    def apply(value: (immutable.Seq[HttpHeader], T), mediaType: MediaType) =
      value._1 -> value._2.withContentType(value._2.contentType withMediaType mediaType).asInstanceOf[T]
  }
  implicit val forResponse = new MediaTypeOverrider[HttpResponse] {
    def apply(value: HttpResponse, mediaType: MediaType) =
      value.mapEntity(forEntity(_: ResponseEntity, mediaType))
  }
  implicit val forRequest = new MediaTypeOverrider[HttpRequest] {
    def apply(value: HttpRequest, mediaType: MediaType) =
      value.mapEntity(forEntity(_: RequestEntity, mediaType))
  }
}

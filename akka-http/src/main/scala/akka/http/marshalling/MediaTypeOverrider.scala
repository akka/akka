/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshalling

import scala.collection.immutable
import akka.http.model._

sealed trait MediaTypeOverrider[T] {
  def apply(value: T, mediaType: MediaType): T
}
object MediaTypeOverrider {
  implicit val forRegularEntity = new MediaTypeOverrider[HttpEntity.Regular] {
    def apply(value: HttpEntity.Regular, mediaType: MediaType) =
      value.withContentType(value.contentType withMediaType mediaType)
  }
  implicit val forEntity = new MediaTypeOverrider[HttpEntity] {
    def apply(value: HttpEntity, mediaType: MediaType) =
      value.withContentType(value.contentType withMediaType mediaType)
  }
  implicit val forHeadersAndRegularEntity = new MediaTypeOverrider[(immutable.Seq[HttpHeader], HttpEntity.Regular)] {
    def apply(value: (immutable.Seq[HttpHeader], HttpEntity.Regular), mediaType: MediaType) =
      value._1 -> value._2.withContentType(value._2.contentType withMediaType mediaType)
  }
  implicit val forHeadersAndEntity = new MediaTypeOverrider[(immutable.Seq[HttpHeader], HttpEntity)] {
    def apply(value: (immutable.Seq[HttpHeader], HttpEntity), mediaType: MediaType) =
      value._1 -> value._2.withContentType(value._2.contentType withMediaType mediaType)
  }
  implicit val forResponse = new MediaTypeOverrider[HttpResponse] {
    def apply(value: HttpResponse, mediaType: MediaType) =
      value.mapEntity(forEntity(_, mediaType))
  }
  implicit val forRequest = new MediaTypeOverrider[HttpRequest] {
    def apply(value: HttpRequest, mediaType: MediaType) =
      value.mapEntity(forEntity(_, mediaType))
  }
}

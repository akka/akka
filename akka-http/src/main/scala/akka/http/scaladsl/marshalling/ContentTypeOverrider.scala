/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshalling

import scala.collection.immutable
import akka.http.scaladsl.model._

sealed trait ContentTypeOverrider[T] {
  def apply(value: T, newContentType: ContentType): T
}

object ContentTypeOverrider {

  implicit def forEntity[T <: HttpEntity]: ContentTypeOverrider[T] = new ContentTypeOverrider[T] {
    def apply(value: T, newContentType: ContentType) =
      value.withContentType(newContentType).asInstanceOf[T] // can't be expressed in types
  }

  implicit def forHeadersAndEntity[T <: HttpEntity]: ContentTypeOverrider[(immutable.Seq[HttpHeader], T)] =
    new ContentTypeOverrider[(immutable.Seq[HttpHeader], T)] {
      def apply(value: (immutable.Seq[HttpHeader], T), newContentType: ContentType) =
        value._1 -> value._2.withContentType(newContentType).asInstanceOf[T]
    }

  implicit val forResponse: ContentTypeOverrider[HttpResponse] =
    new ContentTypeOverrider[HttpResponse] {
      def apply(value: HttpResponse, newContentType: ContentType) =
        value.mapEntity(forEntity(_: ResponseEntity, newContentType))
    }

  implicit val forRequest: ContentTypeOverrider[HttpRequest] =
    new ContentTypeOverrider[HttpRequest] {
      def apply(value: HttpRequest, newContentType: ContentType) =
        value.mapEntity(forEntity(_: RequestEntity, newContentType))
    }
}

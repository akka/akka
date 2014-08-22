/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

import akka.http.model._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.concurrent.{ ExecutionContext, Future }

package object unmarshalling {
  type FromEntityUnmarshaller[T] = Unmarshaller[HttpEntity, T]
  type FromMessageUnmarshaller[T] = Unmarshaller[HttpMessage, T]
  type FromResponseUnmarshaller[T] = Unmarshaller[HttpResponse, T]
  type FromRequestUnmarshaller[T] = Unmarshaller[HttpRequest, T]

  type Deserialized[T] = Future[T]
  type FromStringDeserializer[T] = Deserializer[String, T]
  type FromBodyPartOptionUnmarshaller[T] = Deserializer[Option[BodyPart], T]
  type FromStringOptionDeserializer[T] = Deserializer[Option[String], T]

  trait FormFieldConverter[T] {
    def withDefault(default: T): FormFieldConverter[T]
  }

  sealed trait HttpForm {
    type FieldType
    def fields: Seq[FieldType]
  }

  implicit class ResponseAddAs(res: HttpResponse) {
    def as[T](implicit um: FromResponseUnmarshaller[T], ec: ExecutionContext): Future[T] = um(res).asFuture
  }
  implicit class EntityAddAs(ent: HttpEntity) {
    def as[T](implicit um: FromEntityUnmarshaller[T], ec: ExecutionContext): Future[T] = um(ent).asFuture
    def asString(implicit mat: FlowMaterializer, ec: ExecutionContext): Future[String] = as[String]

    def collectedDataBytes(implicit mat: FlowMaterializer): Future[ByteString] =
      Flow(ent.dataBytes(mat)).fold(ByteString.empty)(_ ++ _).toFuture()
  }
}

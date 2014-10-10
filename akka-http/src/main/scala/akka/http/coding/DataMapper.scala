/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model.{ HttpRequest, HttpResponse, ResponseEntity, RequestEntity }
import akka.stream.Transformer
import akka.util.ByteString

/** An abstraction to transform data bytes of HttpMessages or HttpEntities */
sealed trait DataMapper[T] {
  def transformDataBytes(t: T, transformer: () ⇒ Transformer[ByteString, ByteString]): T
}
object DataMapper {
  implicit val mapRequestEntity: DataMapper[RequestEntity] =
    new DataMapper[RequestEntity] {
      def transformDataBytes(t: RequestEntity, transformer: () ⇒ Transformer[ByteString, ByteString]): RequestEntity =
        t.transformDataBytes(transformer)
    }
  implicit val mapResponseEntity: DataMapper[ResponseEntity] =
    new DataMapper[ResponseEntity] {
      def transformDataBytes(t: ResponseEntity, transformer: () ⇒ Transformer[ByteString, ByteString]): ResponseEntity =
        t.transformDataBytes(transformer)
    }

  implicit val mapRequest: DataMapper[HttpRequest] = mapMessage(mapRequestEntity)((m, f) ⇒ m.withEntity(f(m.entity)))
  implicit val mapResponse: DataMapper[HttpResponse] = mapMessage(mapResponseEntity)((m, f) ⇒ m.withEntity(f(m.entity)))

  def mapMessage[T, E](entityMapper: DataMapper[E])(mapEntity: (T, E ⇒ E) ⇒ T): DataMapper[T] =
    new DataMapper[T] {
      def transformDataBytes(t: T, transformer: () ⇒ Transformer[ByteString, ByteString]): T =
        mapEntity(t, entityMapper.transformDataBytes(_, transformer))
    }
}
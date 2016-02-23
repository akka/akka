/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.http.scaladsl.model.{ HttpRequest, HttpResponse, ResponseEntity, RequestEntity }
import akka.util.ByteString
import akka.stream.scaladsl.Flow

/** An abstraction to transform data bytes of HttpMessages or HttpEntities */
sealed trait DataMapper[T] {
  def transformDataBytes(t: T, transformer: Flow[ByteString, ByteString, _]): T
}
object DataMapper {
  implicit val mapRequestEntity: DataMapper[RequestEntity] =
    new DataMapper[RequestEntity] {
      def transformDataBytes(t: RequestEntity, transformer: Flow[ByteString, ByteString, _]): RequestEntity =
        t.transformDataBytes(transformer)
    }
  implicit val mapResponseEntity: DataMapper[ResponseEntity] =
    new DataMapper[ResponseEntity] {
      def transformDataBytes(t: ResponseEntity, transformer: Flow[ByteString, ByteString, _]): ResponseEntity =
        t.transformDataBytes(transformer)
    }

  implicit val mapRequest: DataMapper[HttpRequest] = mapMessage(mapRequestEntity)((m, f) ⇒ m.withEntity(f(m.entity)))
  implicit val mapResponse: DataMapper[HttpResponse] = mapMessage(mapResponseEntity)((m, f) ⇒ m.withEntity(f(m.entity)))

  def mapMessage[T, E](entityMapper: DataMapper[E])(mapEntity: (T, E ⇒ E) ⇒ T): DataMapper[T] =
    new DataMapper[T] {
      def transformDataBytes(t: T, transformer: Flow[ByteString, ByteString, _]): T =
        mapEntity(t, entityMapper.transformDataBytes(_, transformer))
    }
}

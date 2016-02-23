/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.marshallers.jackson

import com.fasterxml.jackson.databind.{ MapperFeature, ObjectMapper }
import scala.reflect.ClassTag
import akka.http.scaladsl.marshalling
import akka.http.scaladsl.unmarshalling
import akka.http.scaladsl.model.MediaTypes._
import akka.http.javadsl.server.{ Unmarshaller, Marshaller }
import akka.http.impl.server.{ UnmarshallerImpl, MarshallerImpl }

object Jackson {
  private val objectMapper: ObjectMapper = new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
  def json[T <: AnyRef]: Marshaller[T] = jsonMarshaller(objectMapper).asInstanceOf[Marshaller[T]]
  def json[T <: AnyRef](objectMapper: ObjectMapper): Marshaller[T] = jsonMarshaller(objectMapper).asInstanceOf[Marshaller[T]]
  def jsonAs[T](clazz: Class[T]): Unmarshaller[T] = jsonAs(objectMapper, clazz)
  def jsonAs[T](objectMapper: ObjectMapper, clazz: Class[T]): Unmarshaller[T] =
    UnmarshallerImpl[T] {
      unmarshalling.Unmarshaller.messageUnmarshallerFromEntityUnmarshaller { // isn't implicitly inferred for unknown reasons
        unmarshalling.Unmarshaller.stringUnmarshaller
          .forContentTypes(`application/json`)
          .map { jsonString ⇒
            val reader = objectMapper.reader(clazz)
            clazz.cast(reader.readValue(jsonString))
          }
      }
    }(ClassTag(clazz))

  private def jsonMarshaller(objectMapper: ObjectMapper): Marshaller[AnyRef] =
    MarshallerImpl[AnyRef] { implicit ec ⇒
      marshalling.Marshaller.StringMarshaller.wrap(`application/json`) { (value: AnyRef) ⇒
        val writer = objectMapper.writer()
        writer.writeValueAsString(value)
      }
    }
}

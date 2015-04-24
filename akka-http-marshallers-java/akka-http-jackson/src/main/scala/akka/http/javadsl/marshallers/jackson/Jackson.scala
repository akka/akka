/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
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
  def json[T <: AnyRef]: Marshaller[T] = _jsonMarshaller.asInstanceOf[Marshaller[T]]
  def jsonAs[T](clazz: Class[T]): Unmarshaller[T] =
    UnmarshallerImpl[T] { (_ec, _flowMaterializer) ⇒
      implicit val ec = _ec
      implicit val mat = _flowMaterializer

      unmarshalling.Unmarshaller.messageUnmarshallerFromEntityUnmarshaller { // isn't implicitly inferred for unknown reasons
        unmarshalling.Unmarshaller.stringUnmarshaller
          .forContentTypes(`application/json`)
          .map { jsonString ⇒
            val reader = new ObjectMapper().reader(clazz)
            clazz.cast(reader.readValue(jsonString))
          }
      }
    }(ClassTag(clazz))

  private val _jsonMarshaller: Marshaller[AnyRef] =
    MarshallerImpl[AnyRef] { implicit ec ⇒
      marshalling.Marshaller.StringMarshaller.wrap(`application/json`) { (value: AnyRef) ⇒
        val writer = new ObjectMapper().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).writer()
        writer.writeValueAsString(value)
      }
    }
}

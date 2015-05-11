/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import scala.language.implicitConversions
import akka.stream.FlowMaterializer
import akka.http.scaladsl.marshalling.{ ToEntityMarshaller, Marshaller }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.http.scaladsl.model.{ ContentTypes, HttpCharsets }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import spray.json._

/**
 * A trait providing automatic to and from JSON marshalling/unmarshalling using an in-scope *spray-json* protocol.
 */
trait SprayJsonSupport {
  implicit def sprayJsonUnmarshallerConverter[T](reader: RootJsonReader[T])(implicit mat: FlowMaterializer): FromEntityUnmarshaller[T] =
    sprayJsonUnmarshaller(reader, mat)
  implicit def sprayJsonUnmarshaller[T](implicit reader: RootJsonReader[T], mat: FlowMaterializer): FromEntityUnmarshaller[T] =
    sprayJsValueUnmarshaller.map(jsonReader[T].read)
  implicit def sprayJsValueUnmarshaller(implicit mat: FlowMaterializer): FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) ⇒
      val input =
        if (charset == HttpCharsets.`UTF-8`) ParserInput(data.toArray)
        else ParserInput(data.decodeString(charset.nioCharset.name)) // FIXME: identify charset by instance, not by name!
      JsonParser(input)
    }

  implicit def sprayJsonMarshallerConverter[T](writer: RootJsonWriter[T])(implicit printer: JsonPrinter = PrettyPrinter): ToEntityMarshaller[T] =
    sprayJsonMarshaller[T](writer, printer)
  implicit def sprayJsonMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = PrettyPrinter): ToEntityMarshaller[T] =
    sprayJsValueMarshaller[T] compose writer.write
  implicit def sprayJsValueMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = PrettyPrinter): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(ContentTypes.`application/json`)(printer.apply)
}
object SprayJsonSupport extends SprayJsonSupport
/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.marshallers.sprayjson

import akka.http.marshalling.{ ToEntityMarshaller, Marshaller }

import scala.language.implicitConversions

import akka.http.model.HttpCharsets
import akka.http.model.MediaTypes.`application/json`
import akka.http.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.stream.FlowMaterializer
import spray.json._

import scala.concurrent.ExecutionContext

/**
 * A trait providing automatic to and from JSON marshalling/unmarshalling using an in-scope *spray-json* protocol.
 */
trait SprayJsonSupport {
  implicit def sprayJsonUnmarshallerConverter[T](reader: RootJsonReader[T])(implicit ec: ExecutionContext, mat: FlowMaterializer): FromEntityUnmarshaller[T] =
    sprayJsonUnmarshaller(reader, ec, mat)
  implicit def sprayJsonUnmarshaller[T](implicit reader: RootJsonReader[T], ec: ExecutionContext, mat: FlowMaterializer): FromEntityUnmarshaller[T] =
    sprayJsValueUnmarshaller.map(jsonReader[T].read)
  implicit def sprayJsValueUnmarshaller(implicit ec: ExecutionContext, mat: FlowMaterializer): FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller.mapWithCharset { (data, charset) ⇒
      val input =
        if (charset == HttpCharsets.`UTF-8`) ParserInput(data.toArray)
        else ParserInput(data.decodeString(charset.nioCharset.name)) // FIXME
      JsonParser(input)
    }.filterMediaType(`application/json`)

  implicit def sprayJsonMarshallerConverter[T](writer: RootJsonWriter[T])(implicit printer: JsonPrinter = PrettyPrinter, ec: ExecutionContext): ToEntityMarshaller[T] =
    sprayJsonMarshaller[T](writer, printer, ec)
  implicit def sprayJsonMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = PrettyPrinter, ec: ExecutionContext): ToEntityMarshaller[T] =
    sprayJsValueMarshaller[T].compose(writer.write)
  implicit def sprayJsValueMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = PrettyPrinter, ec: ExecutionContext): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(`application/json`)(printer.apply)
}
object SprayJsonSupport extends SprayJsonSupport
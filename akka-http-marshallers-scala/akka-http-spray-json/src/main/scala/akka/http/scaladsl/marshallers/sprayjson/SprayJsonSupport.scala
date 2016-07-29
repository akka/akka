/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.http.scaladsl.marshalling.{ Marshaller, ToByteStringMarshaller, ToEntityMarshaller }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ HttpCharsets, MediaTypes }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import spray.json._

import scala.language.implicitConversions

/**
 * A trait providing automatic to and from JSON marshalling/unmarshalling using an in-scope *spray-json* protocol.
 */
trait SprayJsonSupport {
  implicit def sprayJsonUnmarshallerConverter[T](reader: RootJsonReader[T]): FromEntityUnmarshaller[T] =
    sprayJsonUnmarshaller(reader)
  implicit def sprayJsonUnmarshaller[T](implicit reader: RootJsonReader[T]): FromEntityUnmarshaller[T] =
    sprayJsValueUnmarshaller.map(jsonReader[T].read)
  implicit def sprayJsonByteStringUnmarshaller[T](implicit reader: RootJsonReader[T]): Unmarshaller[ByteString, T] =
    Unmarshaller.withMaterializer[ByteString, JsValue](_ ⇒ implicit mat ⇒ { bs ⇒
      // .compact so addressing into any address is very fast (also for large chunks)
      // TODO we could optimise ByteStrings to better handle lienear access like this (or provide ByteStrings.linearAccessOptimised) 
      // TODO IF it's worth it. 
      val parserInput = new SprayJsonByteStringParserInput(bs.compact)
      FastFuture.successful(JsonParser(parserInput))
    }).map(jsonReader[T].read)
  implicit def sprayJsValueUnmarshaller: FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) ⇒
      val input =
        if (charset == HttpCharsets.`UTF-8`) ParserInput(data.toArray)
        else ParserInput(data.decodeString(charset.nioCharset))
      JsonParser(input)
    }

  implicit def sprayJsonMarshallerConverter[T](writer: RootJsonWriter[T])(implicit printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[T] =
    sprayJsonMarshaller[T](writer, printer)
  implicit def sprayJsonMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[T] =
    sprayJsValueMarshaller compose writer.write
  implicit def sprayJsValueMarshaller(implicit printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(printer)
  implicit def sprayByteStringMarshaller[T](implicit writer: RootJsonFormat[T], printer: JsonPrinter = CompactPrinter): ToByteStringMarshaller[T] =
    sprayJsValueMarshaller.map(s ⇒ ByteString(s.toString)) compose writer.write
}
object SprayJsonSupport extends SprayJsonSupport

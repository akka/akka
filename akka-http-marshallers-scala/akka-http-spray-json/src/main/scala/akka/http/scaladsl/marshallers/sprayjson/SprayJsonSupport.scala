/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.NotUsed
import akka.http.scaladsl.common.EntityStreamingSupport
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{ HttpCharsets, MediaTypes }
import akka.http.scaladsl.unmarshalling.{ FromByteStringUnmarshaller, FromEntityUnmarshaller, Unmarshaller }
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.{ Flow, Keep, Source }
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
  implicit def sprayJsValueUnmarshaller: FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller
      .forContentTypes(`application/json`)
      .andThen(sprayJsValueByteStringUnmarshaller)

  implicit def sprayJsValueByteStringUnmarshaller[T]: FromByteStringUnmarshaller[JsValue] =
    Unmarshaller.withMaterializer[ByteString, JsValue](_ ⇒ implicit mat ⇒ { bs ⇒
      // .compact so addressing into any address is very fast (also for large chunks)
      // TODO we could optimise ByteStrings to better handle linear access like this (or provide ByteStrings.linearAccessOptimised)
      // TODO IF it's worth it.
      val parserInput = new SprayJsonByteStringParserInput(bs.compact)
      FastFuture.successful(JsonParser(parserInput))
    })
  implicit def sprayJsonByteStringUnmarshaller[T](implicit reader: RootJsonReader[T]): FromByteStringUnmarshaller[T] =
    sprayJsValueByteStringUnmarshaller[T].map(jsonReader[T].read)

  // support for as[Source[T, NotUsed]]
  implicit def sprayJsonSourceReader[T](implicit reader: RootJsonReader[T], support: EntityStreamingSupport): FromEntityUnmarshaller[Source[T, NotUsed]] =
    Unmarshaller.withMaterializer { implicit ec ⇒ implicit mat ⇒ e ⇒
      if (support.supported.matches(e.contentType)) {
        val frames = e.dataBytes.via(support.framingDecoder)
        val unmarshal = sprayJsonByteStringUnmarshaller(reader)(_)
        val unmarshallingFlow =
          if (support.unordered) Flow[ByteString].mapAsyncUnordered(support.parallelism)(unmarshal)
          else Flow[ByteString].mapAsync(support.parallelism)(unmarshal)
        val elements = frames.viaMat(unmarshallingFlow)(Keep.right)
        FastFuture.successful(elements)
      } else FastFuture.failed(Unmarshaller.UnsupportedContentTypeException(support.supported))
    }

  //#sprayJsonMarshallerConverter
  implicit def sprayJsonMarshallerConverter[T](writer: RootJsonWriter[T])(implicit printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[T] =
    sprayJsonMarshaller[T](writer, printer)
  //#sprayJsonMarshallerConverter
  implicit def sprayJsonMarshaller[T](implicit writer: RootJsonWriter[T], printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[T] =
    sprayJsValueMarshaller compose writer.write
  implicit def sprayJsValueMarshaller(implicit printer: JsonPrinter = CompactPrinter): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(MediaTypes.`application/json`)(printer)
}
object SprayJsonSupport extends SprayJsonSupport

/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.marshallers.playjson

import scala.language.implicitConversions
import scala.concurrent.ExecutionContext
import akka.stream.FlowMaterializer
import akka.http.scaladsl.marshalling.{ ToEntityMarshaller, Marshaller }
import akka.http.scaladsl.unmarshalling.{ FromEntityUnmarshaller, Unmarshaller }
import akka.http.scaladsl.model.{ ContentTypes, HttpCharsets }
import akka.http.scaladsl.model.MediaTypes.`application/json`
import play.api.libs.json._
import akka.http.scaladsl.model.StatusCodes._
import scala.util.control.NoStackTrace

/**
 * A trait providing automatic to and from JSON marshalling/unmarshalling using an in-scope *play-json* protocol.
 */
trait PlayJsonSupport {

  type Printer = (JsValue ⇒ String)

  def read[T](jsValue: JsValue)(implicit reads: Reads[T]): T = {
    reads.reads(jsValue) match {
      case s: JsSuccess[T] ⇒ s.get
      case e: JsError      ⇒ throw JsResultException(e.errors)
    }
  }

  implicit def playJsonUnmarshallerConverter[T](reads: Reads[T])(implicit ec: ExecutionContext, mat: FlowMaterializer): FromEntityUnmarshaller[T] =
    playJsonUnmarshaller(reads, ec, mat)
  implicit def playJsonUnmarshaller[T](implicit reads: Reads[T], ec: ExecutionContext, mat: FlowMaterializer): FromEntityUnmarshaller[T] =
    playJsValueUnmarshaller.map(read[T])
  implicit def playJsValueUnmarshaller(implicit ec: ExecutionContext, mat: FlowMaterializer): FromEntityUnmarshaller[JsValue] =
    Unmarshaller.byteStringUnmarshaller.forContentTypes(`application/json`).mapWithCharset { (data, charset) ⇒
      if (charset == HttpCharsets.`UTF-8`) Json.parse(data.toArray)
      else Json.parse(data.decodeString(charset.nioCharset.name)) // FIXME: identify charset by instance, not by name!
    }

  implicit def playJsonMarshallerConverter[T](writes: Writes[T])(implicit printer: Printer = Json.prettyPrint, ec: ExecutionContext): ToEntityMarshaller[T] =
    playJsonMarshaller[T](writes, printer, ec)
  implicit def playJsonMarshaller[T](implicit writes: Writes[T], printer: Printer = Json.prettyPrint, ec: ExecutionContext): ToEntityMarshaller[T] =
    playJsValueMarshaller[T].compose(writes.writes)
  implicit def playJsValueMarshaller[T](implicit writes: Writes[T], printer: Printer = Json.prettyPrint, ec: ExecutionContext): ToEntityMarshaller[JsValue] =
    Marshaller.StringMarshaller.wrap(ContentTypes.`application/json`)(printer)
}
object PlayJsonSupport extends PlayJsonSupport

/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.testkit

import akka.http.model.parser.HeaderParser
import akka.http.parsing.{ ParserSettings, HttpHeaderParser }
import akka.util.Timeout
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration._

import scala.reflect.ClassTag

import akka.event.{ Logging, LoggingAdapter }
import akka.http.marshalling._
import akka.http.model._
import HttpMethods._
import akka.http.encoding._
import akka.http.model.headers.{ HttpCredentials, RawHeader }
import akka.http.routing

trait RequestBuilding extends TransformerPipelineSupport {
  type RequestTransformer = HttpRequest ⇒ HttpRequest

  class RequestBuilder(val method: HttpMethod) {
    def apply(): HttpRequest = apply("/")

    def apply(uri: String): HttpRequest = apply[String](uri, None)

    def apply[T: Marshaller](uri: String, content: T): HttpRequest = apply(uri, Some(content))

    def apply[T: Marshaller](uri: String, content: Option[T]): HttpRequest = apply(Uri(uri), content)

    def apply(uri: Uri): HttpRequest = apply[String](uri, None)

    def apply[T: Marshaller](uri: Uri, content: T): HttpRequest = apply(uri, Some(content))

    def apply[T](uri: Uri, content: Option[T])(implicit tMarshaller: Marshaller[T], timeout: Timeout = Timeout(1.second)): HttpRequest =
      content match {
        case None ⇒ HttpRequest(method, uri)
        case Some(value) ⇒
          val entity = Await.result(marshalToEntity(value), timeout.duration)
          HttpRequest(method, uri, Nil, entity)
      }
  }

  val Get = new RequestBuilder(GET)
  val Post = new RequestBuilder(POST)
  val Put = new RequestBuilder(PUT)
  val Patch = new RequestBuilder(PATCH)
  val Delete = new RequestBuilder(DELETE)
  val Options = new RequestBuilder(OPTIONS)
  val Head = new RequestBuilder(HEAD)

  def encode(encoder: Encoder): RequestTransformer = encoder.encode(_)

  def addHeader(header: HttpHeader): RequestTransformer = _.mapHeaders(header +: _)

  def addHeader(headerName: String, headerValue: String): RequestTransformer = {
    val rawHeader = RawHeader(headerName, headerValue)
    // FIXME: where should we take the settings from?
    addHeader(HeaderParser.parseHeader(rawHeader).left.flatMap(_ ⇒ Right(rawHeader)).right.get)
  }

  def addHeaders(first: HttpHeader, more: HttpHeader*): RequestTransformer = _.mapHeaders(_ ++ (first +: more))

  def mapHeaders(f: immutable.Seq[HttpHeader] ⇒ immutable.Seq[HttpHeader]): RequestTransformer = _.mapHeaders(f)

  def removeHeader(headerName: String): RequestTransformer = {
    val selected = (_: HttpHeader).name equalsIgnoreCase headerName
    _ mapHeaders (_ filterNot selected)
  }

  def removeHeader[T <: HttpHeader: ClassTag]: RequestTransformer = {
    val clazz = implicitly[ClassTag[T]].runtimeClass
    val selected = (header: HttpHeader) ⇒ clazz.isInstance(header)
    _ mapHeaders (_ filterNot selected)
  }

  def removeHeaders(names: String*): RequestTransformer = {
    val selected = (header: HttpHeader) ⇒ names exists (_ equalsIgnoreCase header.name)
    _ mapHeaders (_ filterNot selected)
  }

  def addCredentials(credentials: HttpCredentials) = addHeader(headers.Authorization(credentials))

  def logRequest(log: LoggingAdapter, level: Logging.LogLevel = Logging.DebugLevel) = logValue[HttpRequest](log, level)

  def logRequest(logFun: HttpRequest ⇒ Unit) = logValue[HttpRequest](logFun)

  implicit def header2AddHeader(header: HttpHeader): RequestTransformer = addHeader(header)
}

object RequestBuilding extends RequestBuilding
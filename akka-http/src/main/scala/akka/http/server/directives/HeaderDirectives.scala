/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.util.control.NonFatal
import akka.http.model._
import akka.http.util._

trait HeaderDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Extracts an HTTP header value using the given function. If the function result is undefined for all headers the
   * request is rejected with an empty rejection set. If the given function throws an exception the request is rejected
   * with a [[spray.routing.MalformedHeaderRejection]].
   */
  def headerValue[T](f: HttpHeader ⇒ Option[T]): Directive1[T] = {
    val protectedF: HttpHeader ⇒ Option[Either[Rejection, T]] = header ⇒
      try f(header).map(Right.apply)
      catch {
        case NonFatal(e) ⇒ Some(Left(MalformedHeaderRejection(header.name, e.getMessage.nullAsEmpty, Some(e))))
      }

    extract(_.request.headers.collectFirst(Function.unlift(protectedF))).flatMap {
      case Some(Right(a))        ⇒ provide(a)
      case Some(Left(rejection)) ⇒ reject(rejection)
      case None                  ⇒ reject
    }
  }

  /**
   * Extracts an HTTP header value using the given partial function. If the function is undefined for all headers the
   * request is rejected with an empty rejection set.
   */
  def headerValuePF[T](pf: PartialFunction[HttpHeader, T]): Directive1[T] = headerValue(pf.lift)

  /**
   * Extracts the value of the HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[spray.routing.MissingHeaderRejection]].
   */
  def headerValueByName(headerName: Symbol): Directive1[String] = headerValueByName(headerName.toString)

  /**
   * Extracts the value of the HTTP request header with the given name.
   * If no header with a matching name is found the request is rejected with a [[spray.routing.MissingHeaderRejection]].
   */
  def headerValueByName(headerName: String): Directive1[String] =
    headerValue(optionalValue(headerName.toLowerCase)) | reject(MissingHeaderRejection(headerName))

  /**
   * Extracts the HTTP request header of the given type.
   * If no header with a matching type is found the request is rejected with a [[spray.routing.MissingHeaderRejection]].
   */
  def headerValueByType[T <: HttpHeader](magnet: ClassMagnet[T]): Directive1[T] =
    headerValuePF(magnet.extractPF) | reject(MissingHeaderRejection(magnet.runtimeClass.getSimpleName))

  /**
   * Extracts an optional HTTP header value using the given function.
   * If the given function throws an exception the request is rejected
   * with a [[spray.routing.MalformedHeaderRejection]].
   */
  def optionalHeaderValue[T](f: HttpHeader ⇒ Option[T]): Directive1[Option[T]] =
    headerValue(f).map(Some(_): Option[T]).recoverPF {
      case Nil ⇒ provide(None)
    }

  /**
   * Extracts an optional HTTP header value using the given partial function.
   * If the given function throws an exception the request is rejected
   * with a [[spray.routing.MalformedHeaderRejection]].
   */
  def optionalHeaderValuePF[T](pf: PartialFunction[HttpHeader, T]): Directive1[Option[T]] =
    optionalHeaderValue(pf.lift)

  /**
   * Extracts the value of the optional HTTP request header with the given name.
   */
  def optionalHeaderValueByName(headerName: Symbol): Directive1[Option[String]] =
    optionalHeaderValueByName(headerName.toString)

  /**
   * Extracts the value of the optional HTTP request header with the given name.
   */
  def optionalHeaderValueByName(headerName: String): Directive1[Option[String]] = {
    val lowerCaseName = headerName.toLowerCase
    extract(_.request.headers.collectFirst {
      case HttpHeader(`lowerCaseName`, value) ⇒ value
    })
  }

  /**
   * Extract the header value of the optional HTTP request header with the given type.
   */
  def optionalHeaderValueByType[T <: HttpHeader](magnet: ClassMagnet[T]): Directive1[Option[T]] =
    optionalHeaderValuePF(magnet.extractPF)

  private def optionalValue(lowerCaseName: String): HttpHeader ⇒ Option[String] = {
    case HttpHeader(`lowerCaseName`, value) ⇒ Some(value)
    case _                                  ⇒ None
  }
}

object HeaderDirectives extends HeaderDirectives

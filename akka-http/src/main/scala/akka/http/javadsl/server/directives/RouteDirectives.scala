/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import scala.annotation.varargs
import scala.collection.JavaConverters._

import akka.http.impl.model.JavaUri
import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.model.StatusCode
import akka.http.javadsl.model.Uri
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Marshaller
import akka.http.javadsl.server.Route
import akka.http.scaladsl
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.scaladsl.server.Rejection

import akka.http.scaladsl.server.directives.{ RouteDirectives ⇒ D }

abstract class RouteDirectives extends RespondWithDirectives {
  /**
   * Java-specific call added so you can chain together multiple alternate routes using comma,
   * rather than having to explicitly call route1.orElse(route2).orElse(route3).
   */
  @varargs def route(alternatives: Route*): Route = ScalaRoute {
    import akka.http.scaladsl.server.Directives._

    alternatives.map(_.toScala).reduce(_ ~ _)
  }

  /**
   * Rejects the request with the given rejections, or with an empty set of rejections if no rejections are given.
   */
  @varargs def reject(rejections: Rejection*): Route = ScalaRoute(
    D.reject(rejections: _*))

  /**
   * Completes the request with redirection response of the given type to the given URI.
   * @param redirectionType A status code from StatusCodes, which must be a redirection type.
   */
  def redirect(uri: Uri, redirectionType: StatusCode): Route = ScalaRoute(
    redirectionType match {
      case r: Redirection ⇒ D.redirect(uri.asInstanceOf[JavaUri].uri, r)
      case _              ⇒ throw new IllegalArgumentException("Not a valid redirection status code: " + redirectionType)
    })

  /**
   * Bubbles the given error up the response chain, where it is dealt with by the closest `handleExceptions`
   * directive and its ExceptionHandler.
   */
  def failWith(error: Throwable): Route = ScalaRoute(D.failWith(error))

  /**
   * Completes the request using an HTTP 200 OK status code and the given body as UTF-8 entity.
   */
  def complete(body: String): Route = ScalaRoute(
    D.complete(body))

  /**
   * Completes the request using the given http response.
   */
  def complete(response: HttpResponse): Route = ScalaRoute(
    D.complete(response: scaladsl.model.HttpResponse))

  /**
   * Completes the request using the given status code.
   */
  def complete(status: StatusCode): Route = ScalaRoute(
    D.complete(status: scaladsl.model.StatusCode))

  /**
   * Completes the request by marshalling the given value into an http response.
   */
  def complete[T](value: T, marshaller: Marshaller[T, HttpResponse]) = ScalaRoute {
    D.complete(ToResponseMarshallable(value)(marshaller.asScala))
  }

  /**
   * Completes the request using the given status code and headers, marshalling the given value as response entity.
   */
  def complete[T](status: StatusCode, headers: java.lang.Iterable[HttpHeader], value: T, marshaller: Marshaller[T, RequestEntity]) = ScalaRoute {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller(status, headers.asScala.toVector)(marshaller.asScala)))
  }

  /**
   * Completes the request using the given status code, headers, and response entity.
   */
  def complete(status: StatusCode, headers: java.lang.Iterable[HttpHeader], entity: RequestEntity) = ScalaRoute {
    D.complete(scaladsl.model.HttpResponse(status = status, entity = entity, headers = headers.asScala.toVector))
  }

  /**
   * Completes the request using the given status code, marshalling the given value as response entity.
   */
  def complete[T](status: StatusCode, value: T, marshaller: Marshaller[T, RequestEntity]) = ScalaRoute {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller(status)(marshaller.asScala)))
  }

  /**
   * Completes the request using the given status code and response entity.
   */
  def complete(status: StatusCode, entity: RequestEntity) = ScalaRoute {
    D.complete(scaladsl.model.HttpResponse(status = status, entity = entity))
  }

  /**
   * Completes the request using the given status code and the given body as UTF-8.
   */
  def complete(status: StatusCode, entity: String) = ScalaRoute {
    D.complete(scaladsl.model.HttpResponse(status = status, entity = entity))
  }

  /**
   * Completes the request as HTTP 200 OK, adding the given headers, and marshalling the given value as response entity.
   */
  def complete[T](headers: java.lang.Iterable[HttpHeader], value: T, marshaller: Marshaller[T, RequestEntity]) = ScalaRoute {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller(headers = headers.asScala.toVector)(marshaller.asScala)))
  }

  /**
   * Completes the request as HTTP 200 OK, adding the given headers and response entity.
   */
  def complete(headers: java.lang.Iterable[HttpHeader], entity: RequestEntity) = ScalaRoute {
    D.complete(scaladsl.model.HttpResponse(headers = headers.asScala.toVector, entity = entity))
  }

  /**
   * Completes the request as HTTP 200 OK, marshalling the given value as response entity.
   */
  def completeOK[T](value: T, marshaller: Marshaller[T, RequestEntity]) = ScalaRoute {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller()(marshaller.asScala)))
  }

  /**
   * Completes the request as HTTP 200 OK with the given value as response entity.
   */
  def complete(entity: RequestEntity) = ScalaRoute {
    D.complete(scaladsl.model.HttpResponse(entity = entity))
  }
}

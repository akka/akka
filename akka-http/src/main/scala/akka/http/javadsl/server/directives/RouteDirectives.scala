/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.concurrent.CompletionStage

import akka.dispatch.ExecutionContexts
import akka.http.scaladsl.server._
import akka.japi.Util

import scala.collection.immutable.Seq
import scala.annotation.varargs
import scala.collection.JavaConverters._
import akka.http.impl.model.JavaUri
import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.model.HttpResponse
import akka.http.javadsl.model.RequestEntity
import akka.http.javadsl.model.StatusCode
import akka.http.javadsl.model.Uri
import akka.http.javadsl.server.{ RoutingJavaMapping, Rejection, Marshaller, Route }
import akka.http.scaladsl
import akka.http.scaladsl.marshalling.Marshaller._
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.StatusCodes.Redirection
import akka.http.javadsl.server.RoutingJavaMapping._
import akka.http.scaladsl.server.directives.{ RouteDirectives ⇒ D }
import akka.http.scaladsl.util.FastFuture._

abstract class RouteDirectives extends RespondWithDirectives {
  import RoutingJavaMapping.Implicits._

  // Don't try this at home – we only use it here for the java -> scala conversions
  private implicit val conversionExecutionContext = ExecutionContexts.sameThreadExecutionContext

  /**
   * Java-specific call added so you can chain together multiple alternate routes using comma,
   * rather than having to explicitly call route1.orElse(route2).orElse(route3).
   */
  @CorrespondsTo("concat")
  @varargs def route(alternatives: Route*): Route = RouteAdapter {
    import akka.http.scaladsl.server.Directives._

    alternatives.map(_.delegate).reduce(_ ~ _)
  }

  /**
   * Rejects the request with the given rejections, or with an empty set of rejections if no rejections are given.
   */
  @varargs def reject(rejection: Rejection, rejections: Rejection*): Route = RouteAdapter {
    D.reject((rejection +: rejections).map(_.asScala): _*)
  }

  /**
   * Rejects the request with an empty rejection (usualy used for "no directive matched").
   */
  def reject(): Route = RouteAdapter {
    D.reject()
  }

  /**
   * Completes the request with redirection response of the given type to the given URI.
   *
   * @param redirectionType A status code from StatusCodes, which must be a redirection type.
   */
  def redirect(uri: Uri, redirectionType: StatusCode): Route = RouteAdapter {
    redirectionType match {
      case r: Redirection ⇒ D.redirect(uri.asInstanceOf[JavaUri].uri, r)
      case _              ⇒ throw new IllegalArgumentException("Not a valid redirection status code: " + redirectionType)
    }
  }

  /**
   * Bubbles the given error up the response chain, where it is dealt with by the closest `handleExceptions`
   * directive and its ExceptionHandler.
   */
  def failWith(error: Throwable): Route = RouteAdapter(D.failWith(error))

  /**
   * Completes the request using an HTTP 200 OK status code and the given body as UTF-8 entity.
   */
  def complete(body: String): Route = RouteAdapter(
    D.complete(body))

  /**
   * Completes the request using the given http response.
   */
  def complete(response: HttpResponse): Route = RouteAdapter(
    D.complete(response.asScala))

  /**
   * Completes the request using the given status code.
   */
  def complete(status: StatusCode): Route = RouteAdapter(
    D.complete(status.asScala))

  /**
   * Completes the request by marshalling the given value into an http response.
   */
  def complete[T](value: T, marshaller: Marshaller[T, HttpResponse]) = RouteAdapter {
    D.complete(ToResponseMarshallable(value)(marshaller))
  }

  /**
   * Completes the request using the given status code and headers, marshalling the given value as response entity.
   */
  def complete[T](status: StatusCode, headers: java.lang.Iterable[HttpHeader], value: T, marshaller: Marshaller[T, RequestEntity]) = RouteAdapter {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller(status.asScala, Util.immutableSeq(headers).map(_.asScala))(marshaller))) // TODO avoid the map()
  }

  /**
   * Completes the request using the given status code, headers, and response entity.
   */
  def complete(status: StatusCode, headers: java.lang.Iterable[HttpHeader], entity: RequestEntity) = RouteAdapter {
    D.complete(scaladsl.model.HttpResponse(status = status.asScala, entity = entity.asScala, headers = Util.immutableSeq(headers).map(_.asScala))) // TODO avoid the map()
  }

  /**
   * Completes the request using the given status code, marshalling the given value as response entity.
   */
  def complete[T](status: StatusCode, value: T, marshaller: Marshaller[T, RequestEntity]) = RouteAdapter {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller(status.asScala)(marshaller)))
  }

  /**
   * Completes the request using the given status code and response entity.
   */
  def complete(status: StatusCode, entity: RequestEntity) = RouteAdapter {
    D.complete(scaladsl.model.HttpResponse(status = status.asScala, entity = entity.asScala))
  }

  /**
   * Completes the request using the given status code and the given body as UTF-8.
   */
  def complete(status: StatusCode, entity: String) = RouteAdapter {
    D.complete(scaladsl.model.HttpResponse(status = status.asScala, entity = entity))
  }

  /**
   * Completes the request as HTTP 200 OK, adding the given headers, and marshalling the given value as response entity.
   */
  def complete[T](headers: java.lang.Iterable[HttpHeader], value: T, marshaller: Marshaller[T, RequestEntity]) = RouteAdapter {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller(headers = Util.immutableSeq(headers).map(_.asScala))(marshaller))) // TODO can we avoid the map() ?
  }

  /**
   * Completes the request as HTTP 200 OK, adding the given headers and response entity.
   */
  def complete(headers: java.lang.Iterable[HttpHeader], entity: RequestEntity) = RouteAdapter {
    D.complete(scaladsl.model.HttpResponse(headers = headers.asScala.toVector.map(_.asScala), entity = entity.asScala)) // TODO can we avoid the map() ?
  }

  /**
   * Completes the request as HTTP 200 OK, marshalling the given value as response entity.
   */
  @CorrespondsTo("complete")
  def completeOK[T](value: T, marshaller: Marshaller[T, RequestEntity]) = RouteAdapter {
    D.complete(ToResponseMarshallable(value)(fromToEntityMarshaller()(marshaller)))
  }

  /**
   * Completes the request as HTTP 200 OK with the given value as response entity.
   */
  def complete(entity: RequestEntity) = RouteAdapter {
    D.complete(scaladsl.model.HttpResponse(entity = entity.asScala))
  }

  // --- manual "magnet" for Scala Future ---

  /**
   * Completes the request by marshalling the given future value into an http response.
   */
  @CorrespondsTo("complete")
  def completeWithFutureResponse(value: scala.concurrent.Future[HttpResponse]) = RouteAdapter {
    D.complete(value.fast.map(_.asScala))
  }

  /**
   * Completes the request by marshalling the given future value into an http response.
   */
  @CorrespondsTo("complete")
  def completeOKWithFutureString(value: scala.concurrent.Future[String]) = RouteAdapter {
    D.complete(value)
  }

  /**
   * Completes the request using the given future status code.
   */
  @CorrespondsTo("complete")
  def completeWithFutureStatus(status: scala.concurrent.Future[StatusCode]): Route = RouteAdapter {
    D.complete(status.fast.map(_.asScala))
  }

  /**
   * Completes the request by marshalling the given value into an http response.
   */
  @CorrespondsTo("complete")
  def completeOKWithFuture[T](value: scala.concurrent.Future[T], marshaller: Marshaller[T, RequestEntity]) = RouteAdapter {
    D.complete(value.fast.map(v ⇒ ToResponseMarshallable(v)(fromToEntityMarshaller()(marshaller))))
  }

  /**
   * Completes the request by marshalling the given value into an http response.
   */
  @CorrespondsTo("complete")
  def completeWithFuture[T](value: scala.concurrent.Future[T], marshaller: Marshaller[T, HttpResponse]) = RouteAdapter {
    D.complete(value.fast.map(v ⇒ ToResponseMarshallable(v)(marshaller)))
  }

  // --- manual "magnet" for CompletionStage ---

  /**
   * Completes the request by marshalling the given future value into an http response.
   */
  @CorrespondsTo("complete")
  def completeWithFuture(value: CompletionStage[HttpResponse]) = RouteAdapter {
    D.complete(value.asScala.fast.map(_.asScala))
  }

  /**
   * Completes the request by marshalling the given future value into an http response.
   */
  @CorrespondsTo("complete")
  def completeOKWithFuture(value: CompletionStage[RequestEntity]) = RouteAdapter {
    D.complete(value.asScala.fast.map(_.asScala))
  }

  /**
   * Completes the request by marshalling the given future value into an http response.
   */
  @CorrespondsTo("complete")
  def completeOKWithFutureString(value: CompletionStage[String]) = RouteAdapter {
    D.complete(value.asScala)
  }

  /**
   * Completes the request using the given future status code.
   */
  @CorrespondsTo("complete")
  def completeWithFutureStatus(status: CompletionStage[StatusCode]): Route = RouteAdapter {
    D.complete(status.asScala.fast.map(_.asScala))
  }

  /**
   * Completes the request with an `OK` status code by marshalling the given value into an http response.
   */
  @CorrespondsTo("complete")
  def completeOKWithFuture[T](value: CompletionStage[T], marshaller: Marshaller[T, RequestEntity]) = RouteAdapter {
    D.complete(value.asScala.fast.map(v ⇒ ToResponseMarshallable(v)(fromToEntityMarshaller()(marshaller))))
  }

  /**
   * Completes the request by marshalling the given value into an http response.
   */
  @CorrespondsTo("complete")
  def completeWithFuture[T](value: CompletionStage[T], marshaller: Marshaller[T, HttpResponse]) = RouteAdapter {
    D.complete(value.asScala.fast.map(v ⇒ ToResponseMarshallable(v)(marshaller)))
  }

}

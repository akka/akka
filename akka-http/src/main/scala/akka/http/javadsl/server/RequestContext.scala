/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import scala.concurrent.ExecutionContextExecutor
import akka.http.javadsl.model._
import akka.http.javadsl.settings.{ RoutingSettings, ParserSettings }
import akka.stream.Materializer
import java.util.concurrent.CompletionStage

/**
 * The RequestContext represents the state of the request while it is routed through
 * the route structure.
 */
trait RequestContext {
  /**
   * The incoming request.
   */
  def request: HttpRequest

  /**
   * The still unmatched path of the request.
   */
  def unmatchedPath: String

  /** Returns the ExecutionContext of this RequestContext */
  def executionContext(): ExecutionContextExecutor

  /** Returns the Materializer of this RequestContext */
  def materializer(): Materializer

  /**
   * The default RoutingSettings to be used for configuring directives.
   */
  def settings: RoutingSettings

  /**
   * The default ParserSettings to be used for configuring directives.
   */
  def parserSettings: ParserSettings

  /**
   * Completes the request with a value of type T and marshals it using the given
   * marshaller.
   */
  def completeAs[T](marshaller: Marshaller[T], value: T): RouteResult

  /**
   * Completes the request with the given response.
   */
  def complete(response: HttpResponse): RouteResult

  /**
   * Completes the request with the given string as an entity of type `text/plain`.
   */
  def complete(text: String): RouteResult

  /**
   * Completes the request with the given string as an entity of the given type.
   */
  def complete(contentType: ContentType.NonBinary, text: String): RouteResult

  /**
   * Completes the request with the given status code and no entity.
   */
  def completeWithStatus(statusCode: StatusCode): RouteResult

  /**
   * Completes the request with the given status code and no entity.
   */
  def completeWithStatus(statusCode: Int): RouteResult

  /**
   * Defers completion of the request
   */
  def completeWith(futureResult: CompletionStage[RouteResult]): RouteResult

  /**
   * Explicitly rejects the request as not found. Other route alternatives
   * may still be able provide a response.
   */
  def notFound(): RouteResult

  /**
   * Reject this request with an application-defined CustomRejection.
   */
  def reject(customRejection: CustomRejection): RouteResult
}

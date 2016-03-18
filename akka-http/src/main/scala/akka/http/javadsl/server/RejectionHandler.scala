/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.{ lang ⇒ jl }

import akka.http.impl.server.PassRejectionRouteResult
import akka.http.javadsl.model.{ ContentTypeRange, HttpMethod }
import akka.http.javadsl.model.headers.{ HttpChallenge, ByteRange, HttpEncoding }
import akka.http.scaladsl.server.Rejection

/**
 * The base class for defining a RejectionHandler to be used with the `handleRejection` directive.
 * Override one of the handler methods to define a route to be used in case the inner route
 * rejects a request with the given rejection.
 *
 * Default implementations pass the rejection to outer handlers.
 */
abstract class RejectionHandler {
  /**
   * Callback called to handle the empty rejection which represents the
   * "Not Found" condition.
   */
  def handleEmptyRejection(ctx: RequestContext): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by method filters.
   * Signals that the request was rejected because the HTTP method is unsupported.
   *
   * The default implementation does not handle the rejection.
   */
  def handleMethodRejection(ctx: RequestContext, supported: HttpMethod): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by scheme filters.
   * Signals that the request was rejected because the Uri scheme is unsupported.
   */
  def handleSchemeRejection(ctx: RequestContext, supported: String): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by parameter filters.
   * Signals that the request was rejected because a query parameter was not found.
   */
  def handleMissingQueryParamRejection(ctx: RequestContext, parameterName: String): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by parameter filters.
   * Signals that the request was rejected because a query parameter could not be interpreted.
   */
  def handleMalformedQueryParamRejection(ctx: RequestContext, parameterName: String, errorMsg: String,
                                         cause: Throwable): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by form field filters.
   * Signals that the request was rejected because a form field was not found.
   */
  def handleMissingFormFieldRejection(ctx: RequestContext, fieldName: String): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by form field filters.
   * Signals that the request was rejected because a form field could not be interpreted.
   */
  def handleMalformedFormFieldRejection(ctx: RequestContext, fieldName: String, errorMsg: String,
                                        cause: Throwable): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by header directives.
   * Signals that the request was rejected because a required header could not be found.
   */
  def handleMissingHeaderRejection(ctx: RequestContext, headerName: String): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by header directives.
   * Signals that the request was rejected because a header value is malformed.
   */
  def handleMalformedHeaderRejection(ctx: RequestContext, headerName: String, errorMsg: String,
                                     cause: Throwable): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by unmarshallers.
   * Signals that the request was rejected because the requests content-type is unsupported.
   */
  def handleUnsupportedRequestContentTypeRejection(ctx: RequestContext, supported: jl.Iterable[ContentTypeRange]): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by decoding filters.
   * Signals that the request was rejected because the requests content encoding is unsupported.
   */
  def handleUnsupportedRequestEncodingRejection(ctx: RequestContext, supported: HttpEncoding): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by range directives.
   * Signals that the request was rejected because the requests contains only unsatisfiable ByteRanges.
   * The actualEntityLength gives the client a hint to create satisfiable ByteRanges.
   */
  def handleUnsatisfiableRangeRejection(ctx: RequestContext, unsatisfiableRanges: jl.Iterable[ByteRange], actualEntityLength: Long): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by range directives.
   * Signals that the request contains too many ranges. An irregular high number of ranges
   * indicates a broken client or a denial of service attack.
   */
  def handleTooManyRangesRejection(ctx: RequestContext, maxRanges: Int): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by unmarshallers.
   * Signals that the request was rejected because unmarshalling failed with an error that wasn't
   * an `IllegalArgumentException`. Usually that means that the request content was not of the expected format.
   * Note that semantic issues with the request content (e.g. because some parameter was out of range)
   * will usually trigger a `ValidationRejection` instead.
   */
  def handleMalformedRequestContentRejection(ctx: RequestContext, message: String, cause: Throwable): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by unmarshallers.
   * Signals that the request was rejected because an message body entity was expected but not supplied.
   */
  def handleRequestEntityExpectedRejection(ctx: RequestContext): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by marshallers.
   * Signals that the request was rejected because the service is not capable of producing a response entity whose
   * content type is accepted by the client.
   */
  def handleUnacceptedResponseContentTypeRejection(ctx: RequestContext, supported: jl.Iterable[String]): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by encoding filters.
   * Signals that the request was rejected because the service is not capable of producing a response entity whose
   * content encoding is accepted by the client
   */
  def handleUnacceptedResponseEncodingRejection(ctx: RequestContext, supported: jl.Iterable[HttpEncoding]): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by an [[akka.http.javadsl.server.values.HttpBasicAuthenticator]].
   * Signals that the request was rejected because the user could not be authenticated. The reason for the rejection is
   * specified in the cause.
   *
   * If credentialsMissing is false, existing credentials were rejected.
   */
  def handleAuthenticationFailedRejection(ctx: RequestContext, credentialsMissing: Boolean, challenge: HttpChallenge): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by the 'authorize' directive.
   * Signals that the request was rejected because the user is not authorized.
   */
  def handleAuthorizationFailedRejection(ctx: RequestContext): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by the `cookie` directive.
   * Signals that the request was rejected because a cookie was not found.
   */
  def handleMissingCookieRejection(ctx: RequestContext, cookieName: String): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created when a websocket request was expected but none was found.
   */
  def handleExpectedWebSocketRequestRejection(ctx: RequestContext): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created when a websocket request was not handled because none
   * of the given subprotocols was supported.
   */
  def handleUnsupportedWebSocketSubprotocolRejection(ctx: RequestContext, supportedProtocol: String): RouteResult = passRejection()

  /**
   * Callback called to handle rejection created by the `validation` directive as well as for `IllegalArgumentExceptions`
   * thrown by domain model constructors (e.g. via `require`).
   * It signals that an expected value was semantically invalid.
   */
  def handleValidationRejection(ctx: RequestContext, message: String, cause: Throwable): RouteResult = passRejection()

  /**
   * Callback called to handle any custom rejection defined by the application.
   */
  def handleCustomRejection(ctx: RequestContext, rejection: CustomRejection): RouteResult = passRejection()

  /**
   * Callback called to handle any other Scala rejection that is not covered by this class.
   */
  def handleCustomScalaRejection(ctx: RequestContext, rejection: Rejection): RouteResult = passRejection()

  /**
   * Use the RouteResult returned by this method in handler implementations to signal that a rejection was not handled
   * and should be passed to an outer rejection handler.
   */
  protected final def passRejection(): RouteResult = PassRejectionRouteResult
}

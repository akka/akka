/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.collection.immutable
import akka.http.scaladsl.model._
import headers._

/**
 * A rejection encapsulates a specific reason why a Route was not able to handle a request. Rejections are gathered
 * up over the course of a Route evaluation and finally converted to [[akka.http.scaladsl.model.HttpResponse]]s by the
 * `handleRejections` directive, if there was no way for the request to be completed.
 */
trait Rejection

/**
 * Rejection created by method filters.
 * Signals that the request was rejected because the HTTP method is unsupported.
 */
case class MethodRejection(supported: HttpMethod) extends Rejection

/**
 * Rejection created by scheme filters.
 * Signals that the request was rejected because the Uri scheme is unsupported.
 */
case class SchemeRejection(supported: String) extends Rejection

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter was not found.
 */
case class MissingQueryParamRejection(parameterName: String) extends Rejection

/**
 * Rejection created by parameter filters.
 * Signals that the request was rejected because a query parameter could not be interpreted.
 */
case class MalformedQueryParamRejection(parameterName: String, errorMsg: String,
                                        cause: Option[Throwable] = None) extends Rejection

/**
 * Rejection created by form field filters.
 * Signals that the request was rejected because a form field was not found.
 */
case class MissingFormFieldRejection(fieldName: String) extends Rejection

/**
 * Rejection created by form field filters.
 * Signals that the request was rejected because a form field could not be interpreted.
 */
case class MalformedFormFieldRejection(fieldName: String, errorMsg: String,
                                       cause: Option[Throwable] = None) extends Rejection

/**
 * Rejection created by header directives.
 * Signals that the request was rejected because a required header could not be found.
 */
case class MissingHeaderRejection(headerName: String) extends Rejection

/**
 * Rejection created by header directives.
 * Signals that the request was rejected because a header value is malformed.
 */
case class MalformedHeaderRejection(headerName: String, errorMsg: String,
                                    cause: Option[Throwable] = None) extends Rejection

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because the requests content-type is unsupported.
 */
case class UnsupportedRequestContentTypeRejection(supported: immutable.Set[ContentTypeRange]) extends Rejection

/**
 * Rejection created by decoding filters.
 * Signals that the request was rejected because the requests content encoding is unsupported.
 */
case class UnsupportedRequestEncodingRejection(supported: HttpEncoding) extends Rejection

/**
 * Rejection created by range directives.
 * Signals that the request was rejected because the requests contains only unsatisfiable ByteRanges.
 * The actualEntityLength gives the client a hint to create satisfiable ByteRanges.
 */
case class UnsatisfiableRangeRejection(unsatisfiableRanges: immutable.Seq[ByteRange], actualEntityLength: Long) extends Rejection

/**
 * Rejection created by range directives.
 * Signals that the request contains too many ranges. An irregular high number of ranges
 * indicates a broken client or a denial of service attack.
 */
case class TooManyRangesRejection(maxRanges: Int) extends Rejection

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because unmarshalling failed with an error that wasn't
 * an `IllegalArgumentException`. Usually that means that the request content was not of the expected format.
 * Note that semantic issues with the request content (e.g. because some parameter was out of range)
 * will usually trigger a `ValidationRejection` instead.
 */
case class MalformedRequestContentRejection(message: String, cause: Option[Throwable] = None) extends Rejection

/**
 * Rejection created by unmarshallers.
 * Signals that the request was rejected because an message body entity was expected but not supplied.
 */
case object RequestEntityExpectedRejection extends Rejection

/**
 * Rejection created by marshallers.
 * Signals that the request was rejected because the service is not capable of producing a response entity whose
 * content type is accepted by the client
 */
case class UnacceptedResponseContentTypeRejection(supported: immutable.Set[ContentNegotiator.Alternative]) extends Rejection

/**
 * Rejection created by encoding filters.
 * Signals that the request was rejected because the service is not capable of producing a response entity whose
 * content encoding is accepted by the client
 */
case class UnacceptedResponseEncodingRejection(supported: immutable.Set[HttpEncoding]) extends Rejection
object UnacceptedResponseEncodingRejection {
  def apply(supported: HttpEncoding): UnacceptedResponseEncodingRejection = UnacceptedResponseEncodingRejection(Set(supported))
}

/**
 * Rejection created by an [[akka.http.javadsl.server.values.HttpBasicAuthenticator]].
 * Signals that the request was rejected because the user could not be authenticated. The reason for the rejection is
 * specified in the cause.
 */
case class AuthenticationFailedRejection(cause: AuthenticationFailedRejection.Cause,
                                         challenge: HttpChallenge) extends Rejection

object AuthenticationFailedRejection {
  /**
   * Signals the cause of the failed authentication.
   */
  sealed trait Cause

  /**
   * Signals the cause of the rejecting was that the user could not be authenticated, because the `WWW-Authenticate`
   * header was not supplied.
   */
  case object CredentialsMissing extends Cause

  /**
   * Signals the cause of the rejecting was that the user could not be authenticated, because the supplied credentials
   * are invalid.
   */
  case object CredentialsRejected extends Cause
}

/**
 * Rejection created by the 'authorize' directive.
 * Signals that the request was rejected because the user is not authorized.
 */
case object AuthorizationFailedRejection extends Rejection

/**
 * Rejection created by the `cookie` directive.
 * Signals that the request was rejected because a cookie was not found.
 */
case class MissingCookieRejection(cookieName: String) extends Rejection

/**
 * Rejection created when a websocket request was expected but none was found.
 */
case object ExpectedWebSocketRequestRejection extends Rejection

/**
 * Rejection created when a websocket request was not handled because none of the given subprotocols
 * was supported.
 */
case class UnsupportedWebSocketSubprotocolRejection(supportedProtocol: String) extends Rejection

/**
 * Rejection created by the `validation` directive as well as for `IllegalArgumentExceptions`
 * thrown by domain model constructors (e.g. via `require`).
 * It signals that an expected value was semantically invalid.
 */
case class ValidationRejection(message: String, cause: Option[Throwable] = None) extends Rejection

/**
 * A special Rejection that serves as a container for a transformation function on rejections.
 * It is used by some directives to "cancel" rejections that are added by later directives of a similar type.
 *
 * Consider this route structure for example:
 *
 *     put { reject(ValidationRejection("no") } ~ get { ... }
 *
 * If this structure is applied to a PUT request the list of rejections coming back contains three elements:
 *
 * 1. A ValidationRejection
 * 2. A MethodRejection
 * 3. A TransformationRejection holding a function filtering out the MethodRejection
 *
 * so that in the end the RejectionHandler will only see one rejection (the ValidationRejection), because the
 * MethodRejection added by the `get` directive is canceled by the `put` directive (since the HTTP method
 * did indeed match eventually).
 */
case class TransformationRejection(transform: immutable.Seq[Rejection] ⇒ immutable.Seq[Rejection]) extends Rejection

/**
 * A Throwable wrapping a Rejection.
 * Can be used for marshalling `Future[T]` or `Try[T]` instances, whose failure side is supposed to trigger a route
 * rejection rather than an Exception that is handled by the nearest ExceptionHandler.
 * (Custom marshallers can of course use it as well.)
 */
case class RejectionError(rejection: Rejection) extends RuntimeException

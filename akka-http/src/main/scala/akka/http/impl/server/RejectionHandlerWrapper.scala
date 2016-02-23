/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import scala.collection.immutable

import akka.http.javadsl.server
import akka.http.javadsl.server.RouteResult
import akka.http.scaladsl.server._

import akka.http.impl.util.JavaMapping.Implicits._

/**
 * INTERNAL API
 */
private[http] class RejectionHandlerWrapper(javaHandler: server.RejectionHandler) extends RejectionHandler {
  def apply(rejs: immutable.Seq[Rejection]): Option[Route] = Some { scalaCtx ⇒
    val ctx = new RequestContextImpl(scalaCtx)

    import javaHandler._
    def handle(): RouteResult =
      if (rejs.isEmpty) handleEmptyRejection(ctx)
      else rejs.head match {
        case MethodRejection(supported) ⇒
          handleMethodRejection(ctx, supported.asJava)
        case SchemeRejection(supported) ⇒
          handleSchemeRejection(ctx, supported)
        case MissingQueryParamRejection(parameterName) ⇒
          handleMissingQueryParamRejection(ctx, parameterName)
        case MalformedQueryParamRejection(parameterName, errorMsg, cause) ⇒
          handleMalformedQueryParamRejection(ctx, parameterName, errorMsg, cause.orNull)
        case MissingFormFieldRejection(fieldName) ⇒
          handleMissingFormFieldRejection(ctx, fieldName)
        case MalformedFormFieldRejection(fieldName, errorMsg, cause) ⇒
          handleMalformedFormFieldRejection(ctx, fieldName, errorMsg, cause.orNull)
        case MissingHeaderRejection(headerName) ⇒
          handleMissingHeaderRejection(ctx, headerName)
        case MalformedHeaderRejection(headerName, errorMsg, cause) ⇒
          handleMalformedHeaderRejection(ctx, headerName, errorMsg, cause.orNull)
        case UnsupportedRequestContentTypeRejection(supported) ⇒
          handleUnsupportedRequestContentTypeRejection(ctx, supported.toList.toSeq.asJava)
        case UnsupportedRequestEncodingRejection(supported) ⇒
          handleUnsupportedRequestEncodingRejection(ctx, supported.asJava)
        case UnsatisfiableRangeRejection(unsatisfiableRanges, actualEntityLength) ⇒
          handleUnsatisfiableRangeRejection(ctx, unsatisfiableRanges.asJava, actualEntityLength)
        case TooManyRangesRejection(maxRanges) ⇒
          handleTooManyRangesRejection(ctx, maxRanges)
        case MalformedRequestContentRejection(message, cause) ⇒
          handleMalformedRequestContentRejection(ctx, message, cause.orNull)
        case RequestEntityExpectedRejection ⇒
          handleRequestEntityExpectedRejection(ctx)
        case UnacceptedResponseContentTypeRejection(supported) ⇒
          handleUnacceptedResponseContentTypeRejection(ctx, supported.toList.map(_.format).toSeq.asJava)
        case UnacceptedResponseEncodingRejection(supported) ⇒
          handleUnacceptedResponseEncodingRejection(ctx, supported.toList.toSeq.asJava)
        case AuthenticationFailedRejection(cause, challenge) ⇒
          handleAuthenticationFailedRejection(ctx, cause == AuthenticationFailedRejection.CredentialsMissing, challenge)
        case AuthorizationFailedRejection ⇒
          handleAuthorizationFailedRejection(ctx)
        case MissingCookieRejection(cookieName) ⇒
          handleMissingCookieRejection(ctx, cookieName)
        case ExpectedWebSocketRequestRejection ⇒
          handleExpectedWebSocketRequestRejection(ctx)
        case UnsupportedWebSocketSubprotocolRejection(supportedProtocol) ⇒
          handleUnsupportedWebSocketSubprotocolRejection(ctx, supportedProtocol)
        case ValidationRejection(message, cause) ⇒
          handleValidationRejection(ctx, message, cause.orNull)

        case CustomRejectionWrapper(custom) ⇒ handleCustomRejection(ctx, custom)
        case o                              ⇒ handleCustomScalaRejection(ctx, o)
      }

    handle() match {
      case r: RouteResultImpl       ⇒ r.underlying
      case PassRejectionRouteResult ⇒ scalaCtx.reject(rejs: _*)
    }
  }
}

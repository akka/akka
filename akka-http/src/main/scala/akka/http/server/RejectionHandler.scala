/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import scala.concurrent.ExecutionContext
import akka.http.model._
import StatusCodes._
import headers._
import directives.RouteDirectives._
import AuthenticationFailedRejection._

trait RejectionHandler extends RejectionHandler.PF {
  def isDefault: Boolean
}

object RejectionHandler {
  type PF = PartialFunction[List[Rejection], Route]

  implicit def apply(pf: PF): RejectionHandler = apply(default = false)(pf)

  private def apply(default: Boolean)(pf: PF): RejectionHandler =
    new RejectionHandler {
      def isDefault = default
      def isDefinedAt(rejections: List[Rejection]) = pf.isDefinedAt(rejections)
      def apply(rejections: List[Rejection]) = pf(rejections)
    }

  def default(implicit ec: ExecutionContext) = apply(default = true) {
    case Nil ⇒ complete(NotFound, "The requested resource could not be found.")

    case AuthenticationFailedRejection(cause, challengeHeaders) :: _ ⇒
      val rejectionMessage = cause match {
        case CredentialsMissing  ⇒ "The resource requires authentication, which was not supplied with the request"
        case CredentialsRejected ⇒ "The supplied authentication is invalid"
      }
      ctx ⇒ ctx.complete(Unauthorized, challengeHeaders, rejectionMessage)

      case AuthorizationFailedRejection :: _ ⇒
      complete(Forbidden, "The supplied authentication is not authorized to access this resource")

    case CorruptRequestEncodingRejection(msg) :: _ ⇒
      complete(BadRequest, "The requests encoding is corrupt:\n" + msg)

    case MalformedFormFieldRejection(name, msg, _) :: _ ⇒
      complete(BadRequest, "The form field '" + name + "' was malformed:\n" + msg)

    case MalformedHeaderRejection(headerName, msg, _) :: _ ⇒
      complete(BadRequest, s"The value of HTTP header '$headerName' was malformed:\n" + msg)

    case MalformedQueryParamRejection(name, msg, _) :: _ ⇒
      complete(BadRequest, "The query parameter '" + name + "' was malformed:\n" + msg)

    case MalformedRequestContentRejection(msg, _) :: _ ⇒
      complete(BadRequest, "The request content was malformed:\n" + msg)

    case rejections @ (MethodRejection(_) :: _) ⇒
      val methods = rejections.collect { case MethodRejection(method) ⇒ method }
      complete(MethodNotAllowed, List(Allow(methods)), "HTTP method not allowed, supported methods: " + methods.mkString(", "))

    case rejections @ (SchemeRejection(_) :: _) ⇒
      val schemes = rejections.collect { case SchemeRejection(scheme) ⇒ scheme }
      complete(BadRequest, "Uri scheme not allowed, supported schemes: " + schemes.mkString(", "))

    case MissingCookieRejection(cookieName) :: _ ⇒
      complete(BadRequest, "Request is missing required cookie '" + cookieName + '\'')

    case MissingFormFieldRejection(fieldName) :: _ ⇒
      complete(BadRequest, "Request is missing required form field '" + fieldName + '\'')

    case MissingHeaderRejection(headerName) :: _ ⇒
      complete(BadRequest, "Request is missing required HTTP header '" + headerName + '\'')

    case MissingQueryParamRejection(paramName) :: _ ⇒
      complete(NotFound, "Request is missing required query parameter '" + paramName + '\'')

    case RequestEntityExpectedRejection :: _ ⇒
      complete(BadRequest, "Request entity expected but not supplied")

    case TooManyRangesRejection(_) :: _ ⇒
      complete(RequestedRangeNotSatisfiable, "Request contains too many ranges.")

    case UnsatisfiableRangeRejection(unsatisfiableRanges, actualEntityLength) :: _ ⇒
      complete(RequestedRangeNotSatisfiable, List(`Content-Range`(ContentRange.Unsatisfiable(actualEntityLength))),
        unsatisfiableRanges.mkString("None of the following requested Ranges were satisfiable:\n", "\n", ""))

    case rejections @ (UnacceptedResponseContentTypeRejection(_) :: _) ⇒
      val supported = rejections.flatMap {
        case UnacceptedResponseContentTypeRejection(supported) ⇒ supported
        case _ ⇒ Nil
      }
      complete(NotAcceptable, "Resource representation is only available with these Content-Types:\n" + supported.map(_.value).mkString("\n"))

    case rejections @ (UnacceptedResponseEncodingRejection(_) :: _) ⇒
      val supported = rejections.collect { case UnacceptedResponseEncodingRejection(supported) ⇒ supported }
      complete(NotAcceptable, "Resource representation is only available with these Content-Encodings:\n" + supported.map(_.value).mkString("\n"))

    case rejections @ (UnsupportedRequestContentTypeRejection(_) :: _) ⇒
      val supported = rejections.collect { case UnsupportedRequestContentTypeRejection(supported) ⇒ supported }
      complete(UnsupportedMediaType, "There was a problem with the requests Content-Type:\n" + supported.mkString(" or "))

    case rejections @ (UnsupportedRequestEncodingRejection(_) :: _) ⇒
      val supported = rejections.collect { case UnsupportedRequestEncodingRejection(supported) ⇒ supported }
      complete(BadRequest, "The requests Content-Encoding must be one the following:\n" + supported.map(_.value).mkString("\n"))

    case ValidationRejection(msg, _) :: _ ⇒
      complete(BadRequest, msg)

    case x :: _ ⇒ sys.error("Unhandled rejection: " + x)
  }

  /**
   * Filters out all TransformationRejections from the given sequence and applies them (in order) to the
   * remaining rejections.
   */
  def applyTransformations(rejections: List[Rejection]): List[Rejection] = {
    val (transformations, rest) = rejections.partition(_.isInstanceOf[TransformationRejection])
    (rest.distinct /: transformations.asInstanceOf[Seq[TransformationRejection]]) {
      case (remaining, transformation) ⇒ transformation.transform(remaining)
    }
  }
}

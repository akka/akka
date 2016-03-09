/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server

import akka.http.scaladsl.server._
import akka.http.javadsl.model.HttpMethod
import akka.http.javadsl.model.MediaType
import akka.http.scaladsl.model.ContentTypeRange
import akka.http.scaladsl
import akka.http.javadsl.model.headers.HttpEncoding
import akka.http.javadsl.model.headers.ByteRange
import akka.http.javadsl.model.ContentType
import akka.http.javadsl.model.headers.HttpChallenge
import java.util.Optional
import scala.compat.java8.OptionConverters._
import scala.collection.JavaConverters._
import JavaScalaTypeEquivalence._

object Rejections {
  def method(supported: HttpMethod) = MethodRejection(supported)

  def scheme(supported: String) = SchemeRejection(supported)

  def missingQueryParam(parameterName: String) = MissingQueryParamRejection(parameterName)

  def malformedQueryParam(parameterName: String, errorMsg: String) =
    MalformedQueryParamRejection(parameterName, errorMsg)
  def malformedQueryParam(parameterName: String, errorMsg: String, cause: Optional[Throwable]) =
    MalformedQueryParamRejection(parameterName, errorMsg, cause.asScala)

  def missingFormField(fieldName: String) = MissingFormFieldRejection(fieldName)

  def malformedFormField(fieldName: String, errorMsg: String) =
    MalformedFormFieldRejection(fieldName, errorMsg)
  def malformedFormField(fieldName: String, errorMsg: String, cause: Optional[Throwable]) =
    MalformedFormFieldRejection(fieldName, errorMsg, cause.asScala)

  def missingHeader(headerName: String) = MissingHeaderRejection(headerName)

  def malformedHeader(headerName: String, errorMsg: String) =
    MalformedHeaderRejection(headerName, errorMsg)
  def malformedHeader(headerName: String, errorMsg: String, cause: Optional[Throwable]) =
    MalformedHeaderRejection(headerName, errorMsg, cause.asScala)

  def unsupportedRequestContentType(supported: java.lang.Iterable[MediaType]) =
    UnsupportedRequestContentTypeRejection(supported.asScala.map(ContentTypeRange(_)).toSet)

  def unsupportedRequestEncoding(supported: HttpEncoding) = UnsupportedRequestEncodingRejection(supported)

  def unsatisfiableRange(unsatisfiableRanges: java.lang.Iterable[ByteRange], actualEntityLength: Long) =
    UnsatisfiableRangeRejection(unsatisfiableRanges.asScala.toVector, actualEntityLength)

  def tooManyRanges(maxRanges: Int) = TooManyRangesRejection(maxRanges)

  def malformedRequestContent(message: String) =
    MalformedRequestContentRejection(message)
  def malformedRequestContent(message: String, cause: Optional[Throwable]) =
    MalformedRequestContentRejection(message, cause.asScala)

  def requestEntityExpected = RequestEntityExpectedRejection

  def unacceptedResponseContentType(
    supportedContentTypes: java.lang.Iterable[ContentType], supportedMediaTypes: java.lang.Iterable[MediaType]) =
    UnacceptedResponseContentTypeRejection(
      supportedContentTypes.asScala.map(t ⇒ (t: scaladsl.model.ContentType): ContentNegotiator.Alternative).toSet ++
        supportedMediaTypes.asScala.map(t ⇒ (t: scaladsl.model.MediaType): ContentNegotiator.Alternative).toSet)

  def unacceptedResponseEncoding(supported: HttpEncoding) =
    UnacceptedResponseEncodingRejection(supported)
  def unacceptedResponseEncoding(supported: java.lang.Iterable[HttpEncoding]) =
    UnacceptedResponseEncodingRejection(supported.asScala.toSet)

  def authenticationCredentialsMissing(challenge: HttpChallenge) =
    AuthenticationFailedRejection(AuthenticationFailedRejection.CredentialsMissing, challenge)
  def authenticationCredentialsRejected(challenge: HttpChallenge) =
    AuthenticationFailedRejection(AuthenticationFailedRejection.CredentialsRejected, challenge)

  def authorizationFailed = AuthorizationFailedRejection

  def missingCookie(cookieName: String) = MissingCookieRejection(cookieName)

  def expectedWebSocketRequest = ExpectedWebSocketRequestRejection

  def validationRejection(message: String) = ValidationRejection(message)
  def validationRejection(message: String, cause: Optional[Throwable]) = ValidationRejection(message, cause.asScala)

  def transformationRejection(f: java.util.function.Function[java.util.List[Rejection], java.util.List[Rejection]]) =
    TransformationRejection(rejections ⇒ f.apply(rejections.asJava).asScala.toVector)

  def rejectionError(rejection: Rejection) = RejectionError(rejection)
}

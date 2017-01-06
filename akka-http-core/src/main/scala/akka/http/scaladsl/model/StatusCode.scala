/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import language.implicitConversions
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }

/** The result status code of an HTTP response. */
sealed abstract class StatusCode extends jm.StatusCode with LazyValueBytesRenderable {
  def intValue: Int
  def value: String = intValue.toString + ' ' + reason
  def reason: String
  def defaultMessage: String
  def isSuccess: Boolean
  def isFailure: Boolean
  def isRedirection: Boolean
  def allowsEntity: Boolean
}

object StatusCode {
  import StatusCodes._
  implicit def int2StatusCode(code: Int): StatusCode =
    getForKey(code).getOrElse(
      throw new RuntimeException(
        "Non-standard status codes cannot be created by implicit conversion. Use `StatusCodes.custom` instead."))
}

object StatusCodes extends ObjectRegistry[Int, StatusCode] {
  sealed protected abstract class HttpSuccess extends StatusCode {
    def isSuccess = true
    def isFailure = false
  }
  sealed protected abstract class HttpFailure extends StatusCode {
    def isSuccess = false
    def isFailure = true
    def isRedirection: Boolean = false

    def allowsEntity = true
  }

  // format: OFF
  final case class Informational private[StatusCodes] (intValue: Int)(val reason: String,
                                                                val defaultMessage: String) extends HttpSuccess {
    def allowsEntity = false
    def isRedirection: Boolean = false
  }
  final case class Success       private[StatusCodes] (intValue: Int)(val reason: String, val defaultMessage: String,
                                                                val allowsEntity: Boolean = true) extends HttpSuccess {
    def isRedirection: Boolean = false
  }
  final case class Redirection   private[StatusCodes] (intValue: Int)(val reason: String, val defaultMessage: String,
                                                                val htmlTemplate: String, val allowsEntity: Boolean = true) extends HttpSuccess {
    def isRedirection: Boolean = true
  }
  final case class ClientError   private[StatusCodes] (intValue: Int)(val reason: String, val defaultMessage: String) extends HttpFailure
  final case class ServerError   private[StatusCodes] (intValue: Int)(val reason: String, val defaultMessage: String) extends HttpFailure

  final case class CustomStatusCode private[StatusCodes] (intValue: Int)(
    val reason: String,
    val defaultMessage: String,
    val isSuccess: Boolean,
    val allowsEntity: Boolean) extends StatusCode {
    def isFailure: Boolean = !isSuccess
    def isRedirection: Boolean = false
  }

  private def reg[T <: StatusCode](code: T): T = {
    require(getForKey(code.intValue).isEmpty, s"Status code for ${code.intValue} already registered as '${getForKey(code.intValue).get}'.")

    register(code.intValue, code)
  }

  /**
   * Create a custom status code and allow full customization of behavior. The value of `allowsEntity`
   * changes the parser behavior: If it is set to true, a response with this status code is required to include a
   * `Content-Length` header to be parsed correctly when keep-alive is enabled (which is the default in HTTP/1.1).
   * If `allowsEntity` is false, an entity is never expected.
   */
  def custom(intValue: Int, reason: String, defaultMessage: String, isSuccess: Boolean, allowsEntity: Boolean): StatusCode =
    StatusCodes.CustomStatusCode(intValue)(reason, defaultMessage, isSuccess, allowsEntity)

  /** Create a custom status code with default behavior for its value region. */
  def custom(intValue: Int, reason: String, defaultMessage: String = ""): StatusCode =
    if (100 to 199 contains intValue) Informational(intValue)(reason, defaultMessage)
    else if (200 to 299 contains intValue) Success(intValue)(reason, defaultMessage)
    else if (300 to 399 contains intValue) Redirection(intValue)(reason, defaultMessage, defaultMessage)
    else if (400 to 499 contains intValue) ClientError(intValue)(reason, defaultMessage)
    else if (500 to 599 contains intValue) ServerError(intValue)(reason, defaultMessage)
    else throw new IllegalArgumentException("Can't register status code in non-standard region, " +
      "please use the 5-parameter version of custom(...) to provide the additional required information to register this status code.")

  import Informational.{apply => i}
  import Success      .{apply => s}
  import Redirection  .{apply => r}
  import ClientError  .{apply => c}
  import ServerError  .{apply => e}

  // main source: http://en.wikipedia.org/wiki/List_of_HTTP_status_codes

  val Continue           = reg(i(100)("Continue", "The server has received the request headers, and the client should proceed to send the request body."))
  val SwitchingProtocols = reg(i(101)("Switching Protocols", "The server is switching protocols, because the client requested the switch."))
  val Processing         = reg(i(102)("Processing", "The server is processing the request, but no response is available yet."))

  val OK                          = reg(s(200)("OK", "OK"))
  val Created                     = reg(s(201)("Created", "The request has been fulfilled and resulted in a new resource being created."))
  val Accepted                    = reg(s(202)("Accepted", "The request has been accepted for processing, but the processing has not been completed."))
  val NonAuthoritativeInformation = reg(s(203)("Non-Authoritative Information", "The server successfully processed the request, but is returning information that may be from another source."))
  val NoContent                   = reg(s(204)("No Content", "The server successfully processed the request and is not returning any content.", allowsEntity = false))
  val ResetContent                = reg(s(205)("Reset Content", "The server successfully processed the request, but is not returning any content."))
  val PartialContent              = reg(s(206)("Partial Content", "The server is delivering only part of the resource due to a range header sent by the client."))
  val MultiStatus                 = reg(s(207)("Multi-Status", "The message body that follows is an XML message and can contain a number of separate response codes, depending on how many sub-requests were made."))
  val AlreadyReported             = reg(s(208)("Already Reported", "The members of a DAV binding have already been enumerated in a previous reply to this request, and are not being included again."))
  val IMUsed                      = reg(s(226)("IM Used", "The server has fulfilled a GET request for the resource, and the response is a representation of the result of one or more instance-manipulations applied to the current instance."))

  val MultipleChoices   = reg(r(300)("Multiple Choices", "There are multiple options for the resource that the client may follow.", "There are multiple options for the resource that the client may follow. The preferred one is <a href=\"%s\">this URI</a>."))
  val MovedPermanently  = reg(r(301)("Moved Permanently", "This and all future requests should be directed to the given URI.", "This and all future requests should be directed to <a href=\"%s\">this URI</a>."))
  val Found             = reg(r(302)("Found", "The resource was found, but at a different URI.", "The requested resource temporarily resides under <a href=\"%s\">this URI</a>."))
  val SeeOther          = reg(r(303)("See Other", "The response to the request can be found under another URI using a GET method.", "The response to the request can be found under <a href=\"%s\">this URI</a> using a GET method."))
  val NotModified       = reg(r(304)("Not Modified", "The resource has not been modified since last requested.", "", allowsEntity = false))
  val UseProxy          = reg(r(305)("Use Proxy", "This single request is to be repeated via the proxy given by the Location field.", "This single request is to be repeated via the proxy under <a href=\"%s\">this URI</a>."))
  val TemporaryRedirect = reg(r(307)("Temporary Redirect", "The request should be repeated with another URI, but future requests can still use the original URI.", "The request should be repeated with <a href=\"%s\">this URI</a>, but future requests can still use the original URI."))
  val PermanentRedirect = reg(r(308)("Permanent Redirect", "The request, and all future requests should be repeated using another URI.", "The request, and all future requests should be repeated using <a href=\"%s\">this URI</a>."))

  val BadRequest                   = reg(c(400)("Bad Request", "The request contains bad syntax or cannot be fulfilled."))
  val Unauthorized                 = reg(c(401)("Unauthorized", "Authentication is possible but has failed or not yet been provided."))
  val PaymentRequired              = reg(c(402)("Payment Required", "Reserved for future use."))
  val Forbidden                    = reg(c(403)("Forbidden", "The request was a legal request, but the server is refusing to respond to it."))
  val NotFound                     = reg(c(404)("Not Found", "The requested resource could not be found but may be available again in the future."))
  val MethodNotAllowed             = reg(c(405)("Method Not Allowed", "A request was made of a resource using a request method not supported by that resource;"))
  val NotAcceptable                = reg(c(406)("Not Acceptable", "The requested resource is only capable of generating content not acceptable according to the Accept headers sent in the request."))
  val ProxyAuthenticationRequired  = reg(c(407)("Proxy Authentication Required", "Proxy authentication is required to access the requested resource."))
  val RequestTimeout               = reg(c(408)("Request Timeout", "The server timed out waiting for the request."))
  val Conflict                     = reg(c(409)("Conflict", "The request could not be processed because of conflict in the request, such as an edit conflict."))
  val Gone                         = reg(c(410)("Gone", "The resource requested is no longer available and will not be available again."))
  val LengthRequired               = reg(c(411)("Length Required", "The request did not specify the length of its content, which is required by the requested resource."))
  val PreconditionFailed           = reg(c(412)("Precondition Failed", "The server does not meet one of the preconditions that the requester put on the request."))
  val RequestEntityTooLarge        = reg(c(413)("Request Entity Too Large", "The request is larger than the server is willing or able to process."))
  val RequestUriTooLong            = reg(c(414)("Request-URI Too Long", "The URI provided was too long for the server to process."))
  val UnsupportedMediaType         = reg(c(415)("Unsupported Media Type", "The request entity has a media type which the server or resource does not support."))
  val RequestedRangeNotSatisfiable = reg(c(416)("Requested Range Not Satisfiable", "The client has asked for a portion of the file, but the server cannot supply that portion."))
  val ExpectationFailed            = reg(c(417)("Expectation Failed", "The server cannot meet the requirements of the Expect request-header field."))
  val EnhanceYourCalm              = reg(c(420)("Enhance Your Calm", "You are being rate-limited.")) // Twitter only
  val UnprocessableEntity          = reg(c(422)("Unprocessable Entity", "The request was well-formed but was unable to be followed due to semantic errors."))
  val Locked                       = reg(c(423)("Locked", "The resource that is being accessed is locked."))
  val FailedDependency             = reg(c(424)("Failed Dependency", "The request failed due to failure of a previous request."))
  val UnorderedCollection          = reg(c(425)("Unordered Collection", "The collection is unordered."))
  val UpgradeRequired              = reg(c(426)("Upgrade Required", "The client should switch to a different protocol."))
  val PreconditionRequired         = reg(c(428)("Precondition Required", "The server requires the request to be conditional."))
  val TooManyRequests              = reg(c(429)("Too Many Requests", "The user has sent too many requests in a given amount of time."))
  val RequestHeaderFieldsTooLarge  = reg(c(431)("Request Header Fields Too Large", "The server is unwilling to process the request because either an individual header field, or all the header fields collectively, are too large."))
  val RetryWith                    = reg(c(449)("Retry With", "The request should be retried after doing the appropriate action."))
  val BlockedByParentalControls    = reg(c(450)("Blocked by Windows Parental Controls", "Windows Parental Controls are turned on and are blocking access to the given webpage."))
  val UnavailableForLegalReasons   = reg(c(451)("Unavailable For Legal Reasons", "Resource access is denied for legal reasons."))

  val InternalServerError           = reg(e(500)("Internal Server Error", "There was an internal server error."))
  val NotImplemented                = reg(e(501)("Not Implemented", "The server either does not recognize the request method, or it lacks the ability to fulfill the request."))
  val BadGateway                    = reg(e(502)("Bad Gateway", "The server was acting as a gateway or proxy and received an invalid response from the upstream server."))
  val ServiceUnavailable            = reg(e(503)("Service Unavailable", "The server is currently unavailable (because it is overloaded or down for maintenance)."))
  val GatewayTimeout                = reg(e(504)("Gateway Timeout", "The server was acting as a gateway or proxy and did not receive a timely response from the upstream server."))
  val HTTPVersionNotSupported       = reg(e(505)("HTTP Version Not Supported", "The server does not support the HTTP protocol version used in the request."))
  val VariantAlsoNegotiates         = reg(e(506)("Variant Also Negotiates", "Transparent content negotiation for the request, results in a circular reference."))
  val InsufficientStorage           = reg(e(507)("Insufficient Storage", "Insufficient storage to complete the request."))
  val LoopDetected                  = reg(e(508)("Loop Detected", "The server detected an infinite loop while processing the request."))
  val BandwidthLimitExceeded        = reg(e(509)("Bandwidth Limit Exceeded", "Bandwidth limit has been exceeded."))
  val NotExtended                   = reg(e(510)("Not Extended", "Further extensions to the request are required for the server to fulfill it."))
  val NetworkAuthenticationRequired = reg(e(511)("Network Authentication Required", "The client needs to authenticate to gain network access."))
  val NetworkReadTimeout            = reg(e(598)("Network read timeout error", ""))
  val NetworkConnectTimeout         = reg(e(599)("Network connect timeout error", ""))
  // format: ON
}

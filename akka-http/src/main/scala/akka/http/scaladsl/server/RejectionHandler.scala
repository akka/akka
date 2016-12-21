/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection._
import akka.http.scaladsl.server.directives.BasicDirectives

import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag

trait RejectionHandler extends (immutable.Seq[Rejection] ⇒ Option[Route]) { self ⇒
  import RejectionHandler._

  /** Map any HTTP response which was returned by this RejectionHandler to a different one before rendering it. */
  def mapRejectionResponse(map: HttpResponse ⇒ HttpResponse): RejectionHandler = {
    this match {
      case a: BuiltRejectionHandler ⇒
        new BuiltRejectionHandler(
          a.cases.map { handler ⇒ handler.mapResponse(map) },
          a.notFound.map { route ⇒ BasicDirectives.mapResponse(map)(route) },
          isDefault = false)

      case other ⇒
        throw new IllegalArgumentException("Can only mapRejectionResponse on BuiltRejectionHandler " +
          s"(e.g. obtained by calling `.result()` on the `RejectionHandler` builder). Type was: ${other.getClass}")
    }
  }

  /**
   * Creates a new [[RejectionHandler]] which uses the given one as fallback for this one.
   */
  def withFallback(that: RejectionHandler): RejectionHandler =
    (this, that) match {
      case (a: BuiltRejectionHandler, _) if a.isDefault ⇒ this // the default handler already handles everything
      case (a: BuiltRejectionHandler, b: BuiltRejectionHandler) ⇒
        new BuiltRejectionHandler(a.cases ++ b.cases, a.notFound orElse b.notFound, b.isDefault)
      case _ ⇒ new RejectionHandler {
        def apply(rejections: immutable.Seq[Rejection]): Option[Route] =
          self(rejections) orElse that(rejections)
      }
    }

  /**
   * "Seals" this handler by attaching a default handler as fallback if necessary.
   */
  def seal: RejectionHandler =
    this match {
      case x: BuiltRejectionHandler if x.isDefault ⇒ x
      case _                                       ⇒ withFallback(default)
    }
}

object RejectionHandler {

  /**
   * Creates a new [[RejectionHandler]] builder.
   */
  def newBuilder(): Builder = new Builder(isDefault = false)

  final class Builder private[RejectionHandler] (isDefault: Boolean) {
    private[this] val cases = new immutable.VectorBuilder[Handler]
    private[this] var notFound: Option[Route] = None

    /**
     * Handles a single [[Rejection]] with the given partial function.
     */
    def handle(pf: PartialFunction[Rejection, Route]): this.type = {
      cases += CaseHandler(pf)
      this
    }

    /**
     * Handles several Rejections of the same type at the same time.
     * The seq passed to the given function is guaranteed to be non-empty.
     */
    def handleAll[T <: Rejection: ClassTag](f: immutable.Seq[T] ⇒ Route): this.type = {
      val runtimeClass = implicitly[ClassTag[T]].runtimeClass
      cases += TypeHandler[T](runtimeClass, f)
      this
    }

    /**
     * Handles the special "not found" case using the given [[Route]].
     */
    def handleNotFound(route: Route): this.type = {
      notFound = Some(route)
      this
    }

    /**
     * Convenience method for handling rejections created by created by the onCompleteWithBreaker directive.
     * Signals that the request was rejected because the supplied circuit breaker is open and requests are failing fast.
     *
     * Use to customise the error response being written instead of the default [[ServiceUnavailable]] response.
     */
    def handleCircuitBreakerOpenRejection(handler: CircuitBreakerOpenRejection ⇒ Route): this.type =
      handle { case r: CircuitBreakerOpenRejection ⇒ handler(r) }

    def result(): RejectionHandler =
      new BuiltRejectionHandler(cases.result(), notFound, isDefault)
  }

  private sealed abstract class Handler {
    def mapResponse(map: HttpResponse ⇒ HttpResponse): Handler
  }
  private final case class CaseHandler(pf: PartialFunction[Rejection, Route]) extends Handler {
    override def mapResponse(map: HttpResponse ⇒ HttpResponse): CaseHandler = {
      copy(pf.andThen(route ⇒ BasicDirectives.mapResponse(map)(route)))
    }
  }
  private final case class TypeHandler[T <: Rejection](
    runtimeClass: Class[_], f: immutable.Seq[T] ⇒ Route) extends Handler with PartialFunction[Rejection, T] {
    def isDefinedAt(rejection: Rejection): Boolean = runtimeClass isInstance rejection
    def apply(rejection: Rejection): T = rejection.asInstanceOf[T]

    override def mapResponse(map: HttpResponse ⇒ HttpResponse): TypeHandler[T] = {
      copy(f = f.andThen(route ⇒ BasicDirectives.mapResponse(map)(route)))
    }
  }

  private class BuiltRejectionHandler(
    val cases:     Vector[Handler],
    val notFound:  Option[Route],
    val isDefault: Boolean) extends RejectionHandler {
    def apply(rejections: immutable.Seq[Rejection]): Option[Route] =
      if (rejections.nonEmpty) {
        @tailrec def rec(ix: Int): Option[Route] =
          if (ix < cases.length) {
            cases(ix) match {
              case CaseHandler(pf) ⇒
                val route = rejections collectFirst pf
                if (route.isEmpty) rec(ix + 1) else route
              case x @ TypeHandler(_, f) ⇒
                val rejs = rejections collect x
                if (rejs.isEmpty) rec(ix + 1) else Some(f(rejs))
            }
          } else None
        rec(0)
      } else notFound
  }

  import Directives._

  /**
   * Default [[RejectionHandler]] instance.
   */
  final val default =
    newBuilder()
      .handleAll[SchemeRejection] { rejections ⇒
        val schemes = rejections.map(_.supported).mkString(", ")
        complete((BadRequest, "Uri scheme not allowed, supported schemes: " + schemes))
      }
      .handleAll[MethodRejection] { rejections ⇒
        val (methods, names) = rejections.map(r ⇒ r.supported → r.supported.name).unzip
        complete((MethodNotAllowed, List(Allow(methods)), "HTTP method not allowed, supported methods: " + names.mkString(", ")))
      }
      .handle {
        case AuthorizationFailedRejection ⇒
          complete((Forbidden, "The supplied authentication is not authorized to access this resource"))
      }
      .handle {
        case MalformedFormFieldRejection(name, msg, _) ⇒
          complete((BadRequest, "The form field '" + name + "' was malformed:\n" + msg))
      }
      .handle {
        case MalformedHeaderRejection(headerName, msg, _) ⇒
          complete((BadRequest, s"The value of HTTP header '$headerName' was malformed:\n" + msg))
      }
      .handle {
        case MalformedQueryParamRejection(name, msg, _) ⇒
          complete((BadRequest, "The query parameter '" + name + "' was malformed:\n" + msg))
      }
      .handle {
        case MalformedRequestContentRejection(msg, _) ⇒
          complete((BadRequest, "The request content was malformed:\n" + msg))
      }
      .handle {
        case MissingCookieRejection(cookieName) ⇒
          complete((BadRequest, "Request is missing required cookie '" + cookieName + '\''))
      }
      .handle {
        case MissingFormFieldRejection(fieldName) ⇒
          complete((BadRequest, "Request is missing required form field '" + fieldName + '\''))
      }
      .handle {
        case MissingHeaderRejection(headerName) ⇒
          complete((BadRequest, "Request is missing required HTTP header '" + headerName + '\''))
      }
      .handle {
        case InvalidOriginRejection(allowedOrigins) ⇒
          complete((Forbidden, s"Allowed `Origin` header values: ${allowedOrigins.mkString(", ")}"))
      }
      .handle {
        case MissingQueryParamRejection(paramName) ⇒
          complete((NotFound, "Request is missing required query parameter '" + paramName + '\''))
      }
      .handle {
        case RequestEntityExpectedRejection ⇒
          complete((BadRequest, "Request entity expected but not supplied"))
      }
      .handle {
        case TooManyRangesRejection(_) ⇒
          complete((RequestedRangeNotSatisfiable, "Request contains too many ranges"))
      }
      .handle {
        case CircuitBreakerOpenRejection(_) ⇒
          complete(ServiceUnavailable)
      }
      .handle {
        case UnsatisfiableRangeRejection(unsatisfiableRanges, actualEntityLength) ⇒
          complete((RequestedRangeNotSatisfiable, List(`Content-Range`(ContentRange.Unsatisfiable(actualEntityLength))),
            unsatisfiableRanges.mkString("None of the following requested Ranges were satisfiable:\n", "\n", "")))
      }
      .handleAll[AuthenticationFailedRejection] { rejections ⇒
        val rejectionMessage = rejections.head.cause match {
          case CredentialsMissing  ⇒ "The resource requires authentication, which was not supplied with the request"
          case CredentialsRejected ⇒ "The supplied authentication is invalid"
        }
        // Multiple challenges per WWW-Authenticate header are allowed per spec,
        // however, it seems many browsers will ignore all challenges but the first.
        // Therefore, multiple WWW-Authenticate headers are rendered, instead.
        //
        // See https://code.google.com/p/chromium/issues/detail?id=103220
        // and https://bugzilla.mozilla.org/show_bug.cgi?id=669675
        val authenticateHeaders = rejections.map(r ⇒ `WWW-Authenticate`(r.challenge))
        complete((Unauthorized, authenticateHeaders, rejectionMessage))
      }
      .handleAll[UnacceptedResponseContentTypeRejection] { rejections ⇒
        val supported = rejections.flatMap(_.supported)
        val msg = supported.map(_.format).mkString("Resource representation is only available with these types:\n", "\n", "")
        complete((NotAcceptable, msg))
      }
      .handleAll[UnacceptedResponseEncodingRejection] { rejections ⇒
        val supported = rejections.flatMap(_.supported)
        complete((NotAcceptable, "Resource representation is only available with these Content-Encodings:\n" +
          supported.map(_.value).mkString("\n")))
      }
      .handleAll[UnsupportedRequestContentTypeRejection] { rejections ⇒
        val supported = rejections.flatMap(_.supported).mkString(" or ")
        complete((UnsupportedMediaType, "The request's Content-Type is not supported. Expected:\n" + supported))
      }
      .handleAll[UnsupportedRequestEncodingRejection] { rejections ⇒
        val supported = rejections.map(_.supported.value).mkString(" or ")
        complete((BadRequest, "The request's Content-Encoding is not supported. Expected:\n" + supported))
      }
      .handle { case ExpectedWebSocketRequestRejection ⇒ complete((BadRequest, "Expected WebSocket Upgrade request")) }
      .handleAll[UnsupportedWebSocketSubprotocolRejection] { rejections ⇒
        val supported = rejections.map(_.supportedProtocol)
        complete(HttpResponse(
          BadRequest,
          entity = s"None of the websocket subprotocols offered in the request are supported. Supported are ${supported.map("'" + _ + "'").mkString(",")}.",
          headers = `Sec-WebSocket-Protocol`(supported) :: Nil))
      }
      .handle { case ValidationRejection(msg, _) ⇒ complete((BadRequest, msg)) }
      .handle { case x ⇒ sys.error("Unhandled rejection: " + x) }
      .handleNotFound { complete((NotFound, "The requested resource could not be found.")) }
      .result()

  /**
   * Filters out all TransformationRejections from the given sequence and applies them (in order) to the
   * remaining rejections.
   */
  def applyTransformations(rejections: immutable.Seq[Rejection]): immutable.Seq[Rejection] = {
    val (transformations, rest) = rejections.partition(_.isInstanceOf[TransformationRejection])
    (rest.distinct /: transformations.asInstanceOf[Seq[TransformationRejection]]) {
      case (remaining, transformation) ⇒ transformation.transform(remaining)
    }
  }
}

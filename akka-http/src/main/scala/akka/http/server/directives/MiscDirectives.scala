/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.reflect.{ classTag, ClassTag }
import akka.http.model._
import akka.parboiled2.CharPredicate
import headers._
import MediaTypes._
import RouteResult._

trait MiscDirectives {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Returns a Directive which checks the given condition before passing on the [[spray.routing.RequestContext]] to
   * its inner Route. If the condition fails the route is rejected with a [[spray.routing.ValidationRejection]].
   */
  def validate(check: ⇒ Boolean, errorMsg: String): Directive0 =
    new Directive0 {
      def tapply(f: Unit ⇒ Route) = if (check) f() else reject(ValidationRejection(errorMsg))
    }

  /**
   * Directive extracting the IP of the client from either the X-Forwarded-For, Remote-Address or X-Real-IP header
   * (in that order of priority).
   */
  def clientIP: Directive1[RemoteAddress] = MiscDirectives._clientIP

  /**
   * Rejects the request if its entity is not empty.
   */
  def requestEntityEmpty: Directive0 = MiscDirectives._requestEntityEmpty

  /**
   * Rejects empty requests with a RequestEntityExpectedRejection.
   * Non-empty requests are passed on unchanged to the inner route.
   */
  def requestEntityPresent: Directive0 = MiscDirectives._requestEntityPresent

  /**
   * Converts responses with an empty entity into (empty) rejections.
   * This way you can, for example, have the marshalling of a ''None'' option be treated as if the request could
   * not be matched.
   */
  def rejectEmptyResponse: Directive0 = MiscDirectives._rejectEmptyResponse
}

object MiscDirectives extends MiscDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import RouteDirectives._
  import CharPredicate._

  private val validJsonpChars = AlphaNum ++ '.' ++ '_' ++ '$'

  private val _clientIP: Directive1[RemoteAddress] =
    headerValuePF { case `X-Forwarded-For`(Seq(address, _*)) ⇒ address } |
      headerValuePF { case `Remote-Address`(address) ⇒ address } |
      headerValuePF { case h if h.is("x-real-ip") ⇒ RemoteAddress(h.value) }

  private val _requestEntityEmpty: Directive0 =
    extract(_.request.entity.isKnownEmpty).flatMap(if (_) pass else reject)

  private val _requestEntityPresent: Directive0 =
    extract(_.request.entity.isKnownEmpty).flatMap(if (_) reject else pass)

  private val _rejectEmptyResponse: Directive0 =
    mapRouteResponse {
      case Complete(response) if response.entity.isKnownEmpty ⇒ rejected(Nil)
      case x ⇒ x
    }
}

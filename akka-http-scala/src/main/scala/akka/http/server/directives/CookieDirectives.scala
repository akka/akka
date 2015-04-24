/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import akka.http.model._
import headers._
import akka.http.util._

trait CookieDirectives {
  import HeaderDirectives._
  import RespondWithDirectives._
  import RouteDirectives._

  /**
   * Extracts an HttpCookie with the given name. If the cookie is not present the
   * request is rejected with a respective [[MissingCookieRejection]].
   */
  def cookie(name: String): Directive1[HttpCookie] =
    headerValue(findCookie(name)) | reject(MissingCookieRejection(name))

  /**
   * Extracts an HttpCookie with the given name.
   * If the cookie is not present a value of `None` is extracted.
   */
  def optionalCookie(name: String): Directive1[Option[HttpCookie]] =
    optionalHeaderValue(findCookie(name))

  private def findCookie(name: String): HttpHeader ⇒ Option[HttpCookie] = {
    case Cookie(cookies) ⇒ cookies.find(_.name == name)
    case _               ⇒ None
  }

  /**
   * Adds a Set-Cookie header with the given cookies to all responses of its inner route.
   */
  def setCookie(first: HttpCookie, more: HttpCookie*): Directive0 =
    respondWithHeaders((first :: more.toList).map(`Set-Cookie`(_)))

  /**
   * Adds a Set-Cookie header expiring the given cookies to all responses of its inner route.
   */
  def deleteCookie(first: HttpCookie, more: HttpCookie*): Directive0 =
    respondWithHeaders((first :: more.toList).map { c ⇒
      `Set-Cookie`(c.copy(content = "deleted", expires = Some(DateTime.MinValue)))
    })

  /**
   * Adds a Set-Cookie header expiring the given cookie to all responses of its inner route.
   */
  def deleteCookie(name: String, domain: String = "", path: String = ""): Directive0 =
    deleteCookie(HttpCookie(name, "", domain = domain.toOption, path = path.toOption))

}

object CookieDirectives extends CookieDirectives

/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.impl.util._

trait CookieDirectives {
  import HeaderDirectives._
  import RespondWithDirectives._
  import RouteDirectives._

  /**
   * Extracts the [[HttpCookiePair]] with the given name. If the cookie is not present the
   * request is rejected with a respective [[MissingCookieRejection]].
   */
  def cookie(name: String): Directive1[HttpCookiePair] =
    headerValue(findCookie(name)) | reject(MissingCookieRejection(name))

  /**
   * Extracts the [[HttpCookiePair]] with the given name as an `Option[HttpCookiePair]`.
   * If the cookie is not present a value of `None` is extracted.
   */
  def optionalCookie(name: String): Directive1[Option[HttpCookiePair]] =
    optionalHeaderValue(findCookie(name))

  private def findCookie(name: String): HttpHeader ⇒ Option[HttpCookiePair] = {
    case Cookie(cookies) ⇒ cookies.find(_.name == name)
    case _               ⇒ None
  }

  /**
   * Adds a [[Set-Cookie]] response header with the given cookies.
   */
  def setCookie(first: HttpCookie, more: HttpCookie*): Directive0 =
    respondWithHeaders((first :: more.toList).map(`Set-Cookie`(_)))

  /**
   * Adds a [[Set-Cookie]] response header expiring the given cookies.
   */
  def deleteCookie(first: HttpCookie, more: HttpCookie*): Directive0 =
    respondWithHeaders((first :: more.toList).map { c ⇒
      `Set-Cookie`(c.copy(value = "deleted", expires = Some(DateTime.MinValue)))
    })

  /**
   * Adds a [[Set-Cookie]] response header expiring the cookie with the given properties.
   */
  def deleteCookie(name: String, domain: String = "", path: String = ""): Directive0 =
    deleteCookie(HttpCookie(name, "", domain = domain.toOption, path = path.toOption))

}

object CookieDirectives extends CookieDirectives

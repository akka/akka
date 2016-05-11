/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives

import java.lang.{ Iterable ⇒ JIterable }
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Supplier

import scala.collection.JavaConverters._
import akka.http.impl.util.JavaMapping.Implicits._

import akka.http.javadsl.model.headers.HttpCookie
import akka.http.javadsl.model.headers.HttpCookiePair
import akka.http.javadsl.server.Route
import akka.http.scaladsl
import akka.http.scaladsl.server.{ Directives ⇒ D }

abstract class CookieDirectives extends CodingDirectives {
  /**
   * Extracts the [[HttpCookiePair]] with the given name. If the cookie is not present the
   * request is rejected with a respective [[akka.http.javadsl.server.MissingCookieRejection]].
   */
  def cookie(name: String, inner: JFunction[HttpCookiePair, Route]): Route = RouteAdapter {
    D.cookie(name) { c ⇒ inner.apply(c).delegate }
  }

  /**
   * Extracts the [[HttpCookiePair]] with the given name as an `Option[HttpCookiePair]`.
   * If the cookie is not present a value of `None` is extracted.
   */
  def optionalCookie(name: String, inner: JFunction[Optional[HttpCookiePair], Route]): Route = RouteAdapter {
    D.optionalCookie(name) { c ⇒ inner.apply(c.asJava).delegate }
  }

  /**
   * Adds a [[Set-Cookie]] response header with the given cookie.
   */
  def setCookie(cookie: HttpCookie, inner: Supplier[Route]): Route = RouteAdapter {
    D.setCookie(cookie.asScala) { inner.get.delegate }
  }

  /**
   * Adds a [[Set-Cookie]] response header with the given cookies.
   */
  def setCookie(cookies: JIterable[HttpCookie], inner: Supplier[Route]): Route = RouteAdapter {
    cookies.asScala.toList match {
      case head :: tail ⇒
        D.setCookie(head.asScala, tail.map(_.asScala).toVector: _*) {
          inner.get.delegate
        }
      case _ ⇒
        inner.get.delegate
    }
  }

  /**
   * Adds a [[Set-Cookie]] response header expiring the given cookie.
   */
  def deleteCookie(cookie: HttpCookie, inner: Supplier[Route]): Route = RouteAdapter {
    D.deleteCookie(cookie.asScala) { inner.get.delegate }
  }

  /**
   * Adds a [[Set-Cookie]] response header expiring the given cookies.
   */
  def deleteCookie(cookies: JIterable[HttpCookie], inner: Supplier[Route]): Route = RouteAdapter {
    cookies.asScala.toList match {
      case head :: tail ⇒
        D.deleteCookie(head.asScala, tail.map(_.asScala).toSeq: _*) {
          inner.get.delegate
        }
      case _ ⇒
        inner.get.delegate
    }
  }

  /**
   * Adds a [[Set-Cookie]] response header expiring the cookie with the given properties.
   *
   * @param name Name of the cookie to match
   */
  def deleteCookie(name: String, inner: Supplier[Route]): Route = deleteCookie(name, "", "", inner)

  /**
   * Adds a [[Set-Cookie]] response header expiring the cookie with the given properties.
   *
   * @param name Name of the cookie to match
   * @param domain Domain of the cookie to match, or empty string to match any domain
   */
  def deleteCookie(name: String, domain: String, inner: Supplier[Route]): Route = deleteCookie(name, domain, "", inner)

  /**
   * Adds a [[Set-Cookie]] response header expiring the cookie with the given properties.
   *
   * @param name Name of the cookie to match
   * @param domain Domain of the cookie to match, or empty string to match any domain
   * @param path Path of the cookie to match, or empty string to match any path
   */
  def deleteCookie(name: String, domain: String, path: String, inner: Supplier[Route]): Route = RouteAdapter {
    D.deleteCookie(name, domain, path) {
      inner.get.delegate
    }
  }

}

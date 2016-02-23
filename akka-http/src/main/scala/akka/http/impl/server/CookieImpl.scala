/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import java.util.Optional

import akka.http.javadsl.model.headers.HttpCookie
import akka.http.javadsl.server.values.Cookie
import akka.http.javadsl.server.{ Directive, Directives, RequestVal }
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.CookieDirectives._
import akka.http.impl.util.JavaMapping.Implicits._

case class CookieImpl(name: String, domain: Optional[String] = Optional.empty[String], path: Optional[String] = Optional.empty[String]) extends Cookie {
  def withDomain(domain: String): Cookie = copy(domain = Optional.of(domain))
  def withPath(path: String): Cookie = copy(path = Optional.of(path))

  val value: RequestVal[String] =
    new StandaloneExtractionImpl[String] {
      def directive: Directive1[String] = cookie(name).map(_.value)
    }

  def optionalValue(): RequestVal[Optional[String]] =
    new StandaloneExtractionImpl[Optional[String]] {
      def directive: Directive1[Optional[String]] = optionalCookie(name).map(_.map(_.value).asJava)
    }

  def set(value: String): Directive =
    Directives.custom(Directives.setCookie(
      HttpCookie.create(name, value, domain, path), _, _: _*))
}

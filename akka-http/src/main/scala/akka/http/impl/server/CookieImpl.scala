/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.model.headers.HttpCookie
import akka.http.javadsl.server.values.Cookie
import akka.http.javadsl.server.{ Directive, Directives, RequestVal }
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.CookieDirectives._
import akka.japi.Option
import akka.http.impl.util.JavaMapping.Implicits._

case class CookieImpl(name: String, domain: Option[String] = None, path: Option[String] = None) extends Cookie {
  def withDomain(domain: String): Cookie = copy(domain = Option.some(domain))
  def withPath(path: String): Cookie = copy(path = Option.some(path))

  val value: RequestVal[String] =
    new StandaloneExtractionImpl[String] {
      def directive: Directive1[String] = cookie(name).map(_.value)
    }

  def optionalValue(): RequestVal[Option[String]] =
    new StandaloneExtractionImpl[Option[String]] {
      def directive: Directive1[Option[String]] = optionalCookie(name).map(_.map(_.value).asJava)
    }

  def set(value: String): Directive =
    Directives.custom(Directives.setCookie(
      HttpCookie.create(name, value, domain, path), _, _: _*))
}

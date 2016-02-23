/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values

import akka.http.impl.server.{ RouteStructure, CookieImpl }
import akka.http.javadsl.server.{ Directive, RequestVal, Route }
import java.util.Optional

import scala.annotation.varargs
import scala.compat.java8.OptionConverters._

abstract class Cookie {
  def name(): String
  def domain(): Optional[String]
  def path(): Optional[String]

  def withDomain(domain: String): Cookie
  def withPath(path: String): Cookie

  def value(): RequestVal[String]
  def optionalValue(): RequestVal[Optional[String]]

  def set(value: String): Directive

  @varargs
  def delete(innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.DeleteCookie(name(), domain().asScala, path().asScala)(innerRoute, moreInnerRoutes.toList)
}
object Cookies {
  def create(name: String): Cookie = new CookieImpl(name)
}
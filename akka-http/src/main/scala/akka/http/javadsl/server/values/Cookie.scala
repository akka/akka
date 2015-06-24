/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.values

import akka.http.impl.server.{ RouteStructure, CookieImpl }
import akka.http.javadsl.server.{ Directive, RequestVal, Route }
import akka.japi.Option

import scala.annotation.varargs
import scala.collection.immutable

abstract class Cookie {
  def name(): String
  def domain(): Option[String]
  def path(): Option[String]

  def withDomain(domain: String): Cookie
  def withPath(path: String): Cookie

  def value(): RequestVal[String]
  def optionalValue(): RequestVal[Option[String]]

  def set(value: String): Directive

  @varargs
  def delete(innerRoute: Route, moreInnerRoutes: Route*): Route =
    RouteStructure.DeleteCookie(name(), domain(), path())(innerRoute, moreInnerRoutes.toList)
}
object Cookies {
  def create(name: String): Cookie = new CookieImpl(name)
}
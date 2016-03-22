/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values

import java.util.Optional

import akka.http.impl.server.HeaderImpl
import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.RequestVal
import akka.http.scaladsl.model
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.directives.HeaderMagnet

import scala.compat.java8.OptionConverters._
import scala.reflect.{ ClassTag, classTag }

trait Header[T <: HttpHeader] {
  def instance(): RequestVal[T]
  def optionalInstance(): RequestVal[Optional[T]]

  def value(): RequestVal[String]
  def optionalValue(): RequestVal[Optional[String]]
}
object Headers {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.HeaderDirectives._

  def byName(name: String): Header[HttpHeader] =
    HeaderImpl[HttpHeader](name, _ ⇒ optionalHeaderInstanceByName(name.toLowerCase()).map(_.asScala), classTag[HttpHeader])

  def byClass[T <: HttpHeader](clazz: Class[T]): Header[T] =
    HeaderImpl[T](clazz.getSimpleName, ct ⇒ optionalHeaderValueByType(HeaderMagnet.fromUnit(())(ct)), ClassTag(clazz))

  private def optionalHeaderInstanceByName(lowercaseName: String): Directive1[Optional[model.HttpHeader]] =
    extract(_.request.headers.collectFirst {
      case h @ model.HttpHeader(`lowercaseName`, _) ⇒ h
    }.asJava)
}

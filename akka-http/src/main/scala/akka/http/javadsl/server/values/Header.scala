/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.values

import akka.http.impl.server.HeaderImpl
import akka.http.javadsl.model.HttpHeader
import akka.http.javadsl.server.RequestVal
import akka.http.scaladsl.model
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.util.ClassMagnet

import scala.reflect.{ ClassTag, classTag }

trait Header[T <: HttpHeader] {
  def instance(): RequestVal[T]
  def optionalInstance(): RequestVal[Option[T]]

  def value(): RequestVal[String]
  def optionalValue(): RequestVal[Option[String]]
}
object Headers {
  import akka.http.scaladsl.server.directives.BasicDirectives._
  import akka.http.scaladsl.server.directives.HeaderDirectives._

  def byName(name: String): Header[HttpHeader] =
    HeaderImpl[HttpHeader](name, _ ⇒ optionalHeaderInstanceByName(name.toLowerCase()), classTag[HttpHeader])

  def byClass[T <: HttpHeader](clazz: Class[T]): Header[T] =
    HeaderImpl[T](clazz.getSimpleName, ct ⇒ optionalHeaderValueByType(ClassMagnet(ct)), ClassTag(clazz))

  private def optionalHeaderInstanceByName(lowercaseName: String): Directive1[Option[model.HttpHeader]] =
    extract(_.request.headers.collectFirst {
      case h @ model.HttpHeader(`lowercaseName`, _) ⇒ h
    })
}
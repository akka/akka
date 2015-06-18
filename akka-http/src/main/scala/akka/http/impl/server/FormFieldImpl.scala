/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.server.RequestVal
import akka.http.javadsl.server.values.FormField
import akka.http.scaladsl.common.{ StrictForm, NameUnmarshallerReceptacle, NameReceptacle }
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.util.FastFuture
import akka.japi.{ Option ⇒ JOption }

import scala.concurrent.{ Future, ExecutionContext }
import scala.reflect.ClassTag
import akka.http.scaladsl.server.directives.FormFieldDirectives
import akka.http.scaladsl.server.{ Directives, Directive1 }
import FormFieldDirectives._

/**
 * INTERNAL API
 */
private[http] class FormFieldImpl[T, U](receptacle: NameReceptacle[T])(
  implicit fu: FromStrictFormFieldUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U)
  extends StandaloneExtractionImpl[U] with FormField[U] {
  import Directives._

  def directive: Directive1[U] =
    extractMaterializer.flatMap { implicit fm ⇒
      formField(receptacle).map(conv)
    }

  def optional: RequestVal[JOption[U]] =
    new StandaloneExtractionImpl[JOption[U]] {
      def directive: Directive1[JOption[U]] = optionalDirective
    }

  private def optionalDirective: Directive1[JOption[U]] =
    extractMaterializer.flatMap { implicit fm ⇒
      formField(receptacle.?).map(v ⇒ JOption.fromScalaOption(v.map(conv)))
    }

  def withDefault(defaultValue: U): RequestVal[U] =
    new StandaloneExtractionImpl[U] {
      def directive: Directive1[U] = optionalDirective.map(_.getOrElse(defaultValue))
    }
}
object FormFieldImpl {
  def apply[T, U](receptacle: NameReceptacle[T])(implicit fu: FromStrictFormFieldUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U): FormField[U] =
    new FormFieldImpl[T, U](receptacle)(fu, tTag, conv)

  def apply[T, U](receptacle: NameUnmarshallerReceptacle[T])(implicit tTag: ClassTag[U], conv: T ⇒ U): FormField[U] =
    apply(new NameReceptacle[T](receptacle.name))(StrictForm.Field.unmarshallerFromFSU(receptacle.um), tTag, conv)
}
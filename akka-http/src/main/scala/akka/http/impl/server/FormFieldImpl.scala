/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.server

import java.util.Optional

import akka.http.javadsl.server.RequestVal
import akka.http.javadsl.server.values.FormField
import akka.http.scaladsl.common.{ StrictForm, NameUnmarshallerReceptacle, NameReceptacle }
import akka.http.scaladsl.unmarshalling._

import scala.reflect.ClassTag
import akka.http.scaladsl.server.{ Directives, Directive1 }

import scala.compat.java8.OptionConverters._

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

  def optional: RequestVal[Optional[U]] =
    new StandaloneExtractionImpl[Optional[U]] {
      def directive: Directive1[Optional[U]] = optionalDirective
    }

  private def optionalDirective: Directive1[Optional[U]] =
    extractMaterializer.flatMap { implicit fm ⇒
      formField(receptacle.?).map(v ⇒ v.map(conv).asJava)
    }

  def withDefault(defaultValue: U): RequestVal[U] =
    new StandaloneExtractionImpl[U] {
      def directive: Directive1[U] = optionalDirective.map(_.orElse(defaultValue))
    }
}
object FormFieldImpl {
  def apply[T, U](receptacle: NameReceptacle[T])(implicit fu: FromStrictFormFieldUnmarshaller[T], tTag: ClassTag[U], conv: T ⇒ U): FormField[U] =
    new FormFieldImpl[T, U](receptacle)(fu, tTag, conv)

  def apply[T, U](receptacle: NameUnmarshallerReceptacle[T])(implicit tTag: ClassTag[U], conv: T ⇒ U): FormField[U] =
    apply(new NameReceptacle[T](receptacle.name))(StrictForm.Field.unmarshallerFromFSU(receptacle.um), tTag, conv)
}

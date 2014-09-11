/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import akka.http.util.Deferrable
import akka.http.model.ContentTypeRange

trait Unmarshaller[-A, B] extends (A ⇒ Deferrable[B]) {

  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(this andThen (_ map f))

  def flatMap[C](f: B ⇒ Deferrable[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(this andThen (_ flatMap f))

  def mapWithInput[C](f: (A @uncheckedVariance, B) ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(a ⇒ this(a) map (f(a, _)))

  def flatMapWithInput[C](f: (A @uncheckedVariance, B) ⇒ Deferrable[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(a ⇒ this(a) flatMap (f(a, _)))

  def recover[C >: B](pf: PartialFunction[Throwable, C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(this andThen (_ recover pf))

  def withDefaultValue[BB >: B](defaultValue: BB)(implicit ec: ExecutionContext): Unmarshaller[A, BB] =
    recover { case UnmarshallingError.ContentExpected ⇒ defaultValue }
}

object Unmarshaller
  extends GenericUnmarshallers
  with PredefinedFromEntityUnmarshallers {

  def apply[A, B](f: A ⇒ Deferrable[B]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] { def apply(a: A) = f(a) }
}

sealed abstract class UnmarshallingError extends RuntimeException

object UnmarshallingError {
  implicit def toDeferrable[T](error: UnmarshallingError): Deferrable[T] = Deferrable.failed(error)

  case object ContentExpected extends UnmarshallingError

  final case class InvalidContent(errorMessage: String, cause: Option[Throwable] = None) extends UnmarshallingError
  object InvalidContent {
    def apply(errorMessage: String, cause: Throwable) = new InvalidContent(errorMessage, Some(cause))
  }

  final case class UnsupportedContentType(supported: immutable.Seq[ContentTypeRange]) extends UnmarshallingError
}

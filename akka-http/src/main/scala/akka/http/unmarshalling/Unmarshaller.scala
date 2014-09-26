/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.{ Future, ExecutionContext }
import akka.http.util.FastFuture
import akka.http.model.ContentTypeRange
import FastFuture._

trait Unmarshaller[-A, B] extends (A ⇒ Future[B]) {

  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(this andThen (_.fast.map(f)))

  def flatMap[C](f: B ⇒ Future[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(this andThen (_.fast.flatMap(f)))

  def mapWithInput[C](f: (A @uncheckedVariance, B) ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(a ⇒ this(a).fast.map(f(a, _)))

  def flatMapWithInput[C](f: (A @uncheckedVariance, B) ⇒ Future[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(a ⇒ this(a).fast.flatMap(f(a, _)))

  def recover[C >: B](pf: PartialFunction[Throwable, C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller(this andThen (_.fast.recover(pf)))

  def withDefaultValue[BB >: B](defaultValue: BB)(implicit ec: ExecutionContext): Unmarshaller[A, BB] =
    recover { case UnmarshallingError.ContentExpected ⇒ defaultValue }
}

object Unmarshaller
  extends GenericUnmarshallers
  with PredefinedFromEntityUnmarshallers {

  def apply[A, B](f: A ⇒ Future[B]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] { def apply(a: A) = f(a) }
}

sealed abstract class UnmarshallingError extends RuntimeException

object UnmarshallingError {
  implicit def toDeferrable[T](error: UnmarshallingError): Future[T] = FastFuture.failed(error)

  case object ContentExpected extends UnmarshallingError

  final case class InvalidContent(errorMessage: String, cause: Option[Throwable] = None) extends UnmarshallingError
  object InvalidContent {
    def apply(errorMessage: String, cause: Throwable) = new InvalidContent(errorMessage, Some(cause))
  }

  final case class UnsupportedContentType(supported: immutable.Seq[ContentTypeRange]) extends UnmarshallingError
}

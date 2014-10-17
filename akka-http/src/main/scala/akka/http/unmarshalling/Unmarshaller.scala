/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.util.control.NonFatal
import scala.collection.immutable
import scala.concurrent.{ Future, ExecutionContext }
import akka.http.util._
import akka.http.model.ContentTypeRange
import FastFuture._

trait Unmarshaller[-A, B] extends (A ⇒ Future[B]) {

  def transform[C](f: Future[B] ⇒ Future[C]): Unmarshaller[A, C] =
    Unmarshaller { a ⇒ f(this(a)) }

  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    transform(_.fast map f)

  def flatMap[C](f: B ⇒ Future[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    transform(_.fast flatMap f)

  def recover[C >: B](pf: PartialFunction[Throwable, C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    transform(_.fast recover pf)

  def withDefaultValue[BB >: B](defaultValue: BB)(implicit ec: ExecutionContext): Unmarshaller[A, BB] =
    recover { case UnmarshallingError.ContentExpected ⇒ defaultValue }
}

object Unmarshaller
  extends GenericUnmarshallers
  with PredefinedFromEntityUnmarshallers
  with PredefinedFromStringUnmarshallers {

  def apply[A, B](f: A ⇒ Future[B]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] {
      def apply(a: A) =
        try f(a)
        catch { case NonFatal(e) ⇒ FastFuture.failed(UnmarshallingError.InvalidContent(e.getMessage.nullAsEmpty, e)) }
    }

  def strict[A, B](f: A ⇒ B): Unmarshaller[A, B] = Unmarshaller(a ⇒ FastFuture.successful(f(a)))

  implicit def identityUnmarshaller[T]: Unmarshaller[T, T] = Unmarshaller(FastFuture.successful)

  // we don't define these methods directly on `Unmarshaller` due to variance constraints
  implicit class EnhancedUnmarshaller[A, B](val um: Unmarshaller[A, B]) extends AnyVal {
    def mapWithInput[C](f: (A, B) ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
      Unmarshaller(a ⇒ um(a).fast.map(f(a, _)))

    def flatMapWithInput[C](f: (A, B) ⇒ Future[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
      Unmarshaller(a ⇒ um(a).fast.flatMap(f(a, _)))
  }
}

sealed abstract class UnmarshallingError(msg: String, cause: Throwable = null) extends RuntimeException(msg, cause)

object UnmarshallingError {
  case object ContentExpected
    extends UnmarshallingError("Content expected")

  final case class UnsupportedContentType(supported: immutable.Seq[ContentTypeRange])
    extends UnmarshallingError(supported.mkString("Unsupported Content-Type, supported: ", ", ", ""))

  final case class InvalidContent(errorMessage: String, cause: Option[Throwable] = None)
    extends UnmarshallingError(errorMessage, cause.orNull)

  object InvalidContent {
    def apply(errorMessage: String, cause: Throwable) = new InvalidContent(errorMessage, Some(cause))
  }
}

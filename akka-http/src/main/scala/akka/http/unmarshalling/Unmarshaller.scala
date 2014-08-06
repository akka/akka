/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.unmarshalling

import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import akka.http.model.ContentTypeRange

trait Unmarshaller[-A, B] extends (A ⇒ Future[Unmarshalling[B]]) {

  def map[C](f: B ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    mapUnmarshalling(_ map f)

  def flatMap[C](f: B ⇒ Unmarshalling[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    mapUnmarshalling(_ flatMap f)

  def mapWithInput[C](f: (A @uncheckedVariance, B) ⇒ C)(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller { a ⇒ this(a) map (_ map (f(a, _))) }

  def flatMapWithInput[C](f: (A @uncheckedVariance, B) ⇒ Unmarshalling[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller { a ⇒ this(a) map (_ flatMap (f(a, _))) }

  def mapUnmarshalling[C](f: Unmarshalling[B] ⇒ Unmarshalling[C])(implicit ec: ExecutionContext): Unmarshaller[A, C] =
    Unmarshaller { this(_) map f }

  def withDefaultValue[BB >: B](defaultValue: BB)(implicit ec: ExecutionContext): Unmarshaller[A, BB] =
    mapUnmarshalling { _ recover { case Unmarshalling.ContentExpected ⇒ defaultValue } }
}

object Unmarshaller
  extends GenericUnmarshallers
  with PredefinedFromEntityUnmarshallers {
  //with UnmarshallerLifting {

  def apply[A, B](f: A ⇒ Future[Unmarshalling[B]]): Unmarshaller[A, B] =
    new Unmarshaller[A, B] { def apply(a: A) = f(a) }
}

sealed trait Unmarshalling[+A] {
  def isSuccess: Boolean
  def isFailure: Boolean
  def value: A
  def map[B](f: A ⇒ B): Unmarshalling[B]
  def flatMap[B](f: A ⇒ Unmarshalling[B]): Unmarshalling[B]
  def recover[AA >: A](f: PartialFunction[Unmarshalling.Failure, AA]): Unmarshalling[AA]
}

object Unmarshalling {
  final case class Success[+A](value: A) extends Unmarshalling[A] {
    def isSuccess = true
    def isFailure = false
    def map[B](f: A ⇒ B) = Success(f(value))
    def flatMap[B](f: A ⇒ Unmarshalling[B]) = f(value)
    def recover[AA >: A](f: PartialFunction[Unmarshalling.Failure, AA]) = this
  }

  sealed abstract class Failure extends Unmarshalling[Nothing] {
    def isSuccess = false
    def isFailure = true
    def value = sys.error("Expected Unmarshalling.Success but got " + this)
    def map[B](f: Nothing ⇒ B) = this
    def flatMap[B](f: Nothing ⇒ Unmarshalling[B]) = this
    def recover[AA >: Nothing](f: PartialFunction[Unmarshalling.Failure, AA]) =
      if (f isDefinedAt this) Success(f(this)) else this
  }

  case object ContentExpected extends Failure

  final case class InvalidContent(errorMessage: String, cause: Option[Throwable] = None) extends Failure
  object InvalidContent {
    def apply(errorMessage: String, cause: Throwable) = new InvalidContent(errorMessage, Some(cause))
  }

  case class UnsupportedContentType(supported: immutable.Seq[ContentTypeRange]) extends Failure
}
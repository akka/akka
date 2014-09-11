/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import scala.concurrent.duration.Duration
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

// TODO: remove and switch to plain old futures if performance is not significantly better

/**
 * An ADT modelling values that are either strictly available immediately or available at some point in the future.
 * The benefit over a direct `Future` is that mapping and flatMapping doesn't have to be scheduled if the value
 * is strictly available.
 */
sealed trait Deferrable[+A] {
  def map[B](f: A ⇒ B)(implicit ec: ExecutionContext): Deferrable[B]
  def flatMap[B](f: A ⇒ Deferrable[B])(implicit ec: ExecutionContext): Deferrable[B]
  def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext): Unit
  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext): Deferrable[B]
  def toFuture: Future[A]
  def await(timeout: Duration): A
}

object Deferrable {
  def apply[T](value: T): Strict[T] = Strict(value)
  def apply[T](value: Try[T]): Deferrable[T] =
    value match {
      case Success(x) ⇒ Strict(x)
      case Failure(e) ⇒ StrictError(e)
    }
  def apply[T](future: Future[T]): Deferrable[T] =
    future.value match {
      case None    ⇒ NonStrict[T](future)
      case Some(x) ⇒ apply(x)
    }
  def failed[T](error: Throwable): StrictError = StrictError(error)

  case class Strict[A](value: A) extends Deferrable[A] {
    def map[B](f: A ⇒ B)(implicit ec: ExecutionContext) =
      try Strict(f(value))
      catch { case NonFatal(error) ⇒ failed(error) }
    def flatMap[B](f: A ⇒ Deferrable[B])(implicit ec: ExecutionContext) =
      try f(value)
      catch { case NonFatal(error) ⇒ failed(error) }
    def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext) = f(value)
    def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext) = this
    def toFuture = Future.successful(value)
    def await(timeout: Duration) = value
  }

  case class StrictError(error: Throwable) extends Deferrable[Nothing] {
    def map[B](f: Nothing ⇒ B)(implicit ec: ExecutionContext) = this
    def flatMap[B](f: Nothing ⇒ Deferrable[B])(implicit ec: ExecutionContext) = this
    def foreach(f: Nothing ⇒ Unit)(implicit ec: ExecutionContext) = ()
    def recover[B](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext) =
      if (pf isDefinedAt error) {
        try Strict(pf(error))
        catch { case NonFatal(e) ⇒ failed(e) }
      } else this
    def toFuture = Future.failed(error)
    def await(timeout: Duration) = throw error
  }

  case class NonStrict[A](future: Future[A]) extends Deferrable[A] {
    def map[B](f: A ⇒ B)(implicit ec: ExecutionContext) =
      future.value match {
        case None    ⇒ NonStrict(future map f)
        case Some(x) ⇒ Deferrable(x) map f
      }
    def flatMap[B](f: A ⇒ Deferrable[B])(implicit ec: ExecutionContext) =
      future.value match {
        case None    ⇒ NonStrict(future flatMap (x ⇒ f(x).toFuture))
        case Some(x) ⇒ Deferrable(x) flatMap f
      }
    def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext) =
      future.value match {
        case None    ⇒ future foreach f
        case Some(x) ⇒ Deferrable(x) foreach f
      }
    def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext) =
      future.value match {
        case None    ⇒ NonStrict(future recover pf)
        case Some(x) ⇒ Deferrable(x) recover pf
      }
    def toFuture = future
    def await(timeout: Duration) = Await.result(future, timeout)
  }
}
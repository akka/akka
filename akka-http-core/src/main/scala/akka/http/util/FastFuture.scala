/**
 * Copyright (C) 2009-2014 Typestrict Inc. <http://www.typestrict.com>
 */

package akka.http.util

import scala.language.{ implicitConversions, higherKinds }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.Duration
import scala.concurrent._

/**
 * Provides alternative implementations of the basic transformation operations defined on [[Future]],
 * which try to avoid scheduling to an [[ExecutionContext]] if possible, i.e. if the given future
 * value is already present.
 */
class FastFuture[A](val future: Future[A]) extends AnyVal {
  import FastFuture._

  def map[B](f: A ⇒ B)(implicit ec: ExecutionContext): Future[B] = {
    def strictMap(a: A) =
      try FulfilledFuture(f(a))
      catch { case NonFatal(e) ⇒ ErrorFuture(e) }

    future match {
      case FulfilledFuture(a) ⇒ strictMap(a)
      case x: ErrorFuture     ⇒ x
      case _ ⇒ future.value match {
        case None             ⇒ future map f
        case Some(Success(a)) ⇒ strictMap(a)
        case Some(Failure(e)) ⇒ ErrorFuture(e)
      }
    }
  }

  def flatMap[B](f: A ⇒ Future[B])(implicit ec: ExecutionContext): Future[B] = {
    def strictFlatMap(a: A) =
      try f(a)
      catch { case NonFatal(e) ⇒ ErrorFuture(e) }

    future match {
      case FulfilledFuture(a) ⇒ strictFlatMap(a)
      case x: ErrorFuture     ⇒ x
      case _ ⇒ future.value match {
        case None             ⇒ future flatMap f
        case Some(Success(a)) ⇒ strictFlatMap(a)
        case Some(Failure(e)) ⇒ ErrorFuture(e)
      }
    }
  }

  def filter(pred: A ⇒ Boolean)(implicit executor: ExecutionContext): Future[A] =
    flatMap {
      r ⇒ if (pred(r)) future else throw new NoSuchElementException("Future.filter predicate is not satisfied")
    }

  def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext): Unit = {
    def strictForeach(a: A) =
      try f(a)
      catch { case NonFatal(e) ⇒ ec.reportFailure(e) } // behave as if the `foreach` had been scheduled

    future match {
      case FulfilledFuture(a) ⇒ strictForeach(a)
      case x: ErrorFuture     ⇒ // nothing to do
      case _ ⇒ future.value match {
        case None             ⇒ future.foreach(f)
        case Some(Success(a)) ⇒ strictForeach(a)
        case Some(Failure(e)) ⇒ // nothing to do
      }
    }
  }

  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext): Future[B] = {
    def strictRecover(t: Throwable) =
      try if (pf isDefinedAt t) FulfilledFuture(pf(t)) else future
      catch { case NonFatal(e) ⇒ ErrorFuture(e) }

    future match {
      case FulfilledFuture(_) ⇒ future
      case ErrorFuture(e)     ⇒ strictRecover(e)
      case _ ⇒ future.value match {
        case None             ⇒ future recover pf
        case Some(Success(a)) ⇒ FulfilledFuture(a)
        case Some(Failure(e)) ⇒ strictRecover(e)
      }
    }
  }
}

object FastFuture {
  def successful[T](value: T): Future[T] = FulfilledFuture(value)
  def failed(error: Throwable): Future[Nothing] = ErrorFuture(error)

  private case class FulfilledFuture[+A](a: A) extends Future[A] {
    def value = Some(Success(a))
    def onComplete[U](f: Try[A] ⇒ U)(implicit executor: ExecutionContext) = Future.successful(a).onComplete(f)
    def isCompleted = true
    def result(atMost: Duration)(implicit permit: CanAwait) = a
    def ready(atMost: Duration)(implicit permit: CanAwait) = this
  }
  private case class ErrorFuture(error: Throwable) extends Future[Nothing] {
    def value = Some(Failure(error))
    def onComplete[U](f: Try[Nothing] ⇒ U)(implicit executor: ExecutionContext) = Future.failed(error).onComplete(f)
    def isCompleted = true
    def result(atMost: Duration)(implicit permit: CanAwait) = throw error
    def ready(atMost: Duration)(implicit permit: CanAwait) = this
  }

  implicit class EnhancedFuture[T](val future: Future[T]) extends AnyVal {
    def fast: FastFuture[T] = new FastFuture[T](future)
  }

  def sequence[T, M[_] <: TraversableOnce[_]](in: M[Future[T]])(implicit cbf: CanBuildFrom[M[Future[T]], T, M[T]], executor: ExecutionContext): Future[M[T]] =
    in.foldLeft(successful(cbf(in))) {
      (fr, fa) ⇒ for (r ← fr.fast; a ← fa.asInstanceOf[Future[T]].fast) yield r += a
    }.fast.map(_.result())

  def fold[T, R](futures: TraversableOnce[Future[T]])(zero: R)(f: (R, T) ⇒ R)(implicit executor: ExecutionContext): Future[R] =
    if (futures.isEmpty) successful(zero)
    else sequence(futures).fast.map(_.foldLeft(zero)(f))

  def reduce[T, R >: T](futures: TraversableOnce[Future[T]])(op: (R, T) ⇒ R)(implicit executor: ExecutionContext): Future[R] =
    if (futures.isEmpty) failed(new NoSuchElementException("reduce attempted on empty collection"))
    else sequence(futures).fast.map(_ reduceLeft op)

  def traverse[A, B, M[_] <: TraversableOnce[_]](in: M[A])(fn: A ⇒ Future[B])(implicit cbf: CanBuildFrom[M[A], B, M[B]], executor: ExecutionContext): Future[M[B]] =
    in.foldLeft(successful(cbf(in))) { (fr, a) ⇒
      val fb = fn(a.asInstanceOf[A])
      for (r ← fr.fast; b ← fb.fast) yield r += b
    }.fast.map(_.result())
}
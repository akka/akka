/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.util

import scala.language.higherKinds
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }
import scala.collection.generic.CanBuildFrom
import scala.concurrent.duration.Duration
import scala.concurrent._

/**
 * Provides alternative implementations of the basic transformation operations defined on [[scala.concurrent.Future]],
 * which try to avoid scheduling to an [[scala.concurrent.ExecutionContext]] if possible, i.e. if the given future
 * value is already present.
 */
class FastFuture[A](val future: Future[A]) extends AnyVal {
  import FastFuture._

  def map[B](f: A ⇒ B)(implicit ec: ExecutionContext): Future[B] =
    transformWith(a ⇒ FastFuture.successful(f(a)), FastFuture.failed)

  def flatMap[B](f: A ⇒ Future[B])(implicit ec: ExecutionContext): Future[B] =
    transformWith(f, FastFuture.failed)

  def filter(pred: A ⇒ Boolean)(implicit executor: ExecutionContext): Future[A] =
    flatMap { r ⇒
      if (pred(r)) future
      else throw new NoSuchElementException("Future.filter predicate is not satisfied") // FIXME: avoid stack trace generation
    }

  def foreach(f: A ⇒ Unit)(implicit ec: ExecutionContext): Unit = map(f)

  def transformWith[B](f: Try[A] ⇒ Future[B])(implicit executor: ExecutionContext): Future[B] =
    transformWith(a ⇒ f(Success(a)), e ⇒ f(Failure(e)))

  def transformWith[B](s: A ⇒ Future[B], f: Throwable ⇒ Future[B])(implicit executor: ExecutionContext): Future[B] = {
    def strictTransform[T](x: T, f: T ⇒ Future[B]) =
      try f(x)
      catch { case NonFatal(e) ⇒ ErrorFuture(e) }

    future match {
      case FulfilledFuture(a) ⇒ strictTransform(a, s)
      case ErrorFuture(e)     ⇒ strictTransform(e, f)
      case _ ⇒ future.value match {
        case None ⇒
          val p = Promise[B]()
          future.onComplete {
            case Success(a) ⇒ p completeWith strictTransform(a, s)
            case Failure(e) ⇒ p completeWith strictTransform(e, f)
          }
          p.future
        case Some(Success(a)) ⇒ strictTransform(a, s)
        case Some(Failure(e)) ⇒ strictTransform(e, f)
      }
    }
  }

  def recover[B >: A](pf: PartialFunction[Throwable, B])(implicit ec: ExecutionContext): Future[B] =
    transformWith(FastFuture.successful, t ⇒ if (pf isDefinedAt t) FastFuture.successful(pf(t)) else future)

  def recoverWith[B >: A](pf: PartialFunction[Throwable, Future[B]])(implicit ec: ExecutionContext): Future[B] =
    transformWith(FastFuture.successful, t ⇒ pf.applyOrElse(t, (_: Throwable) ⇒ future))
}

object FastFuture {
  def apply[T](value: Try[T]): Future[T] = value match {
    case Success(t) ⇒ FulfilledFuture(t)
    case Failure(e) ⇒ ErrorFuture(e)
  }
  private[this] val _successful: Any ⇒ Future[Any] = FulfilledFuture.apply
  def successful[T]: T ⇒ Future[T] = _successful.asInstanceOf[T ⇒ Future[T]]
  val failed: Throwable ⇒ Future[Nothing] = ErrorFuture.apply

  private case class FulfilledFuture[+A](a: A) extends Future[A] {
    def value = Some(Success(a))
    def onComplete[U](f: Try[A] ⇒ U)(implicit executor: ExecutionContext) = Future.successful(a).onComplete(f)
    def isCompleted = true
    def result(atMost: Duration)(implicit permit: CanAwait) = a
    def ready(atMost: Duration)(implicit permit: CanAwait) = this
    def transform[S](f: scala.util.Try[A] ⇒ scala.util.Try[S])(implicit executor: scala.concurrent.ExecutionContext): scala.concurrent.Future[S] =
      FastFuture(f(Success(a)))
    def transformWith[S](f: scala.util.Try[A] ⇒ scala.concurrent.Future[S])(implicit executor: scala.concurrent.ExecutionContext): scala.concurrent.Future[S] =
      new FastFuture(this).transformWith(f)
  }
  private case class ErrorFuture(error: Throwable) extends Future[Nothing] {
    def value = Some(Failure(error))
    def onComplete[U](f: Try[Nothing] ⇒ U)(implicit executor: ExecutionContext) = Future.failed(error).onComplete(f)
    def isCompleted = true
    def result(atMost: Duration)(implicit permit: CanAwait) = throw error
    def ready(atMost: Duration)(implicit permit: CanAwait) = this
    def transform[S](f: scala.util.Try[Nothing] ⇒ scala.util.Try[S])(implicit executor: scala.concurrent.ExecutionContext): scala.concurrent.Future[S] =
      FastFuture(f(Failure(error)))
    def transformWith[S](f: scala.util.Try[Nothing] ⇒ scala.concurrent.Future[S])(implicit executor: scala.concurrent.ExecutionContext): scala.concurrent.Future[S] =
      new FastFuture(this).transformWith(f)
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

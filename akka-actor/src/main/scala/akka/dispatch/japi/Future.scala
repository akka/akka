/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.japi

import akka.util.Timeout
import akka.japi.{ Procedure2, Procedure, Function ⇒ JFunc, Option ⇒ JOption }

@deprecated("Do not use this directly, use subclasses of this", "2.0")
class Callback[-T] extends PartialFunction[T, Unit] {
  override final def isDefinedAt(t: T): Boolean = true
  override final def apply(t: T): Unit = on(t)
  protected def on(result: T): Unit = ()
}

abstract class OnSuccess[-T] extends Callback[T] {
  protected final override def on(result: T) = onSuccess(result)
  def onSuccess(result: T): Unit
}

abstract class OnFailure extends Callback[Throwable] {
  protected final override def on(failure: Throwable) = onFailure(failure)
  def onFailure(failure: Throwable): Unit
}

abstract class OnComplete[-T] extends Callback[Either[Throwable, T]] {
  protected final override def on(value: Either[Throwable, T]): Unit = value match {
    case Left(t)  ⇒ onComplete(t, null.asInstanceOf[T])
    case Right(r) ⇒ onComplete(null, r)
  }
  def onComplete(failure: Throwable, success: T): Unit
}

@deprecated("Do not use this directly, use 'Recover'", "2.0")
class RecoverBridge[+T] extends PartialFunction[Throwable, T] {
  override final def isDefinedAt(t: Throwable): Boolean = true
  override final def apply(t: Throwable): T = on(t)
  protected def on(result: Throwable): T = null.asInstanceOf[T]
}

abstract class Recover[+T] extends RecoverBridge[T] {
  protected final override def on(result: Throwable): T = recover(result)
  def recover(failure: Throwable): T
}

abstract class Filter[-T] extends (T ⇒ Boolean) {
  override final def apply(t: T): Boolean = filter(t)
  def filter(result: T): Boolean
}

abstract class Foreach[-T] extends (T ⇒ Unit) {
  override final def apply(t: T): Unit = each(t)
  def each(result: T): Unit
}

abstract class Mapper[-T, +R] extends (T ⇒ R)

/*
map => A => B
flatMap => A => F[B]
foreach
*/
/* Java API */
trait Future[+T] { self: akka.dispatch.Future[T] ⇒
  /**
   * Asynchronously called when this Future gets a successful result
   */
  private[japi] final def onSuccess[A >: T](proc: Procedure[A]): this.type = self.onSuccess({ case r ⇒ proc(r.asInstanceOf[A]) }: PartialFunction[T, Unit])

  /**
   * Asynchronously called when this Future gets a failed result
   */
  private[japi] final def onFailure(proc: Procedure[Throwable]): this.type = self.onFailure({ case t: Throwable ⇒ proc(t) }: PartialFunction[Throwable, Unit])

  /**
   * Asynchronously called when this future is completed with either a failed or a successful result
   * In case of a success, the first parameter (Throwable) will be null
   * In case of a failure, the second parameter (T) will be null
   * For no reason will both be null or neither be null
   */
  private[japi] final def onComplete[A >: T](proc: Procedure2[Throwable, A]): this.type = self.onComplete(_.fold(t ⇒ proc(t, null.asInstanceOf[T]), r ⇒ proc(null, r)))

  /**
   * Asynchronously applies the provided function to the (if any) successful result of this Future
   * Any failure of this Future will be propagated to the Future returned by this method.
   */
  private[japi] final def map[A >: T, B](f: JFunc[A, B]): akka.dispatch.Future[B] = self.map(f(_))

  /**
   * Asynchronously applies the provided function to the (if any) successful result of this Future and flattens it.
   * Any failure of this Future will be propagated to the Future returned by this method.
   */
  private[japi] final def flatMap[A >: T, B](f: JFunc[A, akka.dispatch.Future[B]]): akka.dispatch.Future[B] = self.flatMap(f(_))

  /**
   * Asynchronously applies the provided Procedure to the (if any) successful result of this Future
   * Provided Procedure will not be called in case of no-result or in case of failed result
   */
  private[japi] final def foreach[A >: T](proc: Procedure[A]): Unit = self.foreach(proc(_))

  /**
   * Returns a new Future whose successful result will be the successful result of this Future if that result conforms to the provided predicate
   * Any failure of this Future will be propagated to the Future returned by this method.
   */
  private[japi] final def filter[A >: T](p: JFunc[A, java.lang.Boolean]): akka.dispatch.Future[A] =
    self.filter((a: Any) ⇒ p(a.asInstanceOf[A])).asInstanceOf[akka.dispatch.Future[A]]
}


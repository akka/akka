/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.japi

import akka.japi.{ Procedure2, Procedure, Function ⇒ JFunc }

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
   * Apply this transformation in case the future is completed with a failure.
   * The supplied Function should rethrow the exception if recovery is not possible.
   */
  private[japi] final def recover[A >: T](f: JFunc[Throwable, A]): akka.dispatch.Future[A] =
    self recover ({ case x ⇒ f(x) }: PartialFunction[Throwable, A])

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

  /**
   * Returns a new Future whose value will be of the specified type if it really is
   * Or a failure with a ClassCastException if it wasn't.
   */
  private[japi] final def mapTo[A](clazz: Class[A]): akka.dispatch.Future[A] = {
    implicit val manifest: Manifest[A] = Manifest.classType(clazz)
    self.mapTo[A]
  }
}


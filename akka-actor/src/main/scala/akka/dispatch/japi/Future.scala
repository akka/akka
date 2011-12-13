/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.japi

import akka.japi.{ Procedure, Function ⇒ JFunc, Option ⇒ JOption }
import akka.actor.Timeout

/* Java API */
trait Future[+T] { self: akka.dispatch.Future[T] ⇒
  private[japi] final def onSuccess[A >: T](proc: Procedure[A]): this.type = self.onSuccess({ case r ⇒ proc(r.asInstanceOf[A]) }: PartialFunction[T, Unit])
  private[japi] final def onFailure(proc: Procedure[Throwable]): this.type = self.onFailure({ case t: Throwable ⇒ proc(t) }: PartialFunction[Throwable, Unit])
  private[japi] final def onComplete[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onComplete(proc(_))
  private[japi] final def map[A >: T, B](f: JFunc[A, B]): akka.dispatch.Future[B] = self.map(f(_))
  private[japi] final def flatMap[A >: T, B](f: JFunc[A, akka.dispatch.Future[B]]): akka.dispatch.Future[B] = self.flatMap(f(_))
  private[japi] final def foreach[A >: T](proc: Procedure[A]): Unit = self.foreach(proc(_))
  private[japi] final def filter[A >: T](p: JFunc[A, java.lang.Boolean]): akka.dispatch.Future[A] =
    self.filter((a: Any) ⇒ p(a.asInstanceOf[A])).asInstanceOf[akka.dispatch.Future[A]]
}


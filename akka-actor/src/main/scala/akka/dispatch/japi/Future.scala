/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dispatch.japi

import akka.japi.{ Procedure, Function ⇒ JFunc, Option ⇒ JOption }
import akka.actor.Timeout

/* Java API */
trait Future[+T] { self: akka.dispatch.Future[T] ⇒
  private[japi] final def onTimeout[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onTimeout(proc(_))
  private[japi] final def onResult[A >: T](proc: Procedure[A]): this.type = self.onResult({ case r ⇒ proc(r.asInstanceOf[A]) }: PartialFunction[T, Unit])
  private[japi] final def onException(proc: Procedure[Throwable]): this.type = self.onException({ case t: Throwable ⇒ proc(t) }: PartialFunction[Throwable, Unit])
  private[japi] final def onComplete[A >: T](proc: Procedure[akka.dispatch.Future[A]]): this.type = self.onComplete(proc(_))
  private[japi] final def map[A >: T, B](f: JFunc[A, B], timeout: Timeout): akka.dispatch.Future[B] = {
    implicit val t = timeout
    self.map(f(_))
  }
  private[japi] final def flatMap[A >: T, B](f: JFunc[A, akka.dispatch.Future[B]], timeout: Timeout): akka.dispatch.Future[B] = {
    implicit val t = timeout
    self.flatMap(f(_))
  }
  private[japi] final def foreach[A >: T](proc: Procedure[A]): Unit = self.foreach(proc(_))
  private[japi] final def filter[A >: T](p: JFunc[A, java.lang.Boolean], timeout: Timeout): akka.dispatch.Future[A] = {
    implicit val t = timeout
    self.filter((a: Any) ⇒ p(a.asInstanceOf[A])).asInstanceOf[akka.dispatch.Future[A]]
  }
}


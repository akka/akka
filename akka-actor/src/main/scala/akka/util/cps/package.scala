/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import scala.util.Timeout
import scala.util.continuations._
import akka.dispatch.MessageDispatcher

package object cps {
  def matchC[A, B, C, D](in: A)(pf: PartialFunction[A, B @cpsParam[C, D]]): B @cpsParam[C, D] = pf(in)

  def loopC[A, U](block: ⇒ U @cps[A])(implicit loop: CPSLoop[A], dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] =
    loop.loopC(block)

  def whileC[A, U](test: ⇒ Boolean)(block: ⇒ U @cps[A])(implicit loop: CPSLoop[A], dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] =
    loop.whileC(test)(block)

  def repeatC[A, U](times: Int)(block: ⇒ U @cps[A])(implicit loop: CPSLoop[A], dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] =
    loop.repeatC(times)(block)
}

package cps {
  object CPSLoop extends DefaultCPSLoop {

    implicit object FutureCPSLoop extends FutureCPSLoop
  }

  trait CPSLoop[A] {
    def loopC[U](block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A]
    def whileC[U](test: ⇒ Boolean)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A]
    def repeatC[U](times: Int)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A]
  }

  import akka.dispatch.{ Future, Promise }
  class FutureCPSLoop extends CPSLoop[Future[Any]] {

    def loopC[U](block: ⇒ U @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        Future(reify(block) flatMap (_ ⇒ reify(loopC(block))) foreach c)
      }

    def whileC[U](test: ⇒ Boolean)(block: ⇒ U @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        if (test)
          Future(reify(block) flatMap (_ ⇒ reify(whileC(test)(block))) foreach c)
        else
          Promise() success (shiftUnitR[Unit, Future[Any]](()) foreach c)
      }

    def repeatC[U](times: Int)(block: ⇒ U @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        if (times > 0)
          Future(reify(block) flatMap (_ ⇒ reify(repeatC(times - 1)(block))) foreach c)
        else
          Promise() success (shiftUnitR[Unit, Future[Any]](()) foreach c)
      }
  }

  trait DefaultCPSLoop {
    implicit def defaultCPSLoop[A] = new CPSLoop[A] {

      def loopC[U](block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] = {
        block
        loopC(block)
      }

      def whileC[U](test: ⇒ Boolean)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] = {
        if (test) {
          block
          whileC(test)(block)
        }
      }

      def repeatC[U](times: Int)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] = {
        if (times > 0) {
          block
          repeatC(times - 1)(block)
        }
      }

    }
  }
}

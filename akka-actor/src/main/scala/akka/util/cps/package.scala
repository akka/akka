/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.util

import scala.util.continuations._
import akka.dispatch.MessageDispatcher

package object cps {
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  def matchC[A, B, C, D](in: A)(pf: PartialFunction[A, B @cpsParam[C, D]]): B @cpsParam[C, D] = pf(in)
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  def loopC[A, U](block: ⇒ U @cps[A])(implicit loop: CPSLoop[A], dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] =
    loop.loopC(block)
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  def whileC[A, U](test: ⇒ Boolean)(block: ⇒ U @cps[A])(implicit loop: CPSLoop[A], dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] =
    loop.whileC(test)(block)
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  def repeatC[A, U](times: Int)(block: ⇒ U @cps[A])(implicit loop: CPSLoop[A], dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] =
    loop.repeatC(times)(block)
}

package cps {
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  object CPSLoop extends DefaultCPSLoop {

    implicit object FutureCPSLoop extends FutureCPSLoop
  }
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  trait CPSLoop[A] {
    def loopC[U](block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A]
    def whileC[U](test: ⇒ Boolean)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A]
    def repeatC[U](times: Int)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A]
  }

  import akka.dispatch.{ Future, Promise }
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  class FutureCPSLoop extends CPSLoop[Future[Any]] {
    @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
    def loopC[U](block: ⇒ U @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        Future(reify(block) flatMap (_ ⇒ reify(loopC(block))) foreach c)
      }
    @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
    def whileC[U](test: ⇒ Boolean)(block: ⇒ U @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        if (test)
          Future(reify(block) flatMap (_ ⇒ reify(whileC(test)(block))) foreach c)
        else
          Promise() success (shiftUnitR[Unit, Future[Any]](()) foreach c)
      }
    @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
    def repeatC[U](times: Int)(block: ⇒ U @cps[Future[Any]])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        if (times > 0)
          Future(reify(block) flatMap (_ ⇒ reify(repeatC(times - 1)(block))) foreach c)
        else
          Promise() success (shiftUnitR[Unit, Future[Any]](()) foreach c)
      }
  }
  @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
  trait DefaultCPSLoop {
    @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
    implicit def defaultCPSLoop[A] = new CPSLoop[A] {
      @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
      def loopC[U](block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] = {
        block
        loopC(block)
      }
      @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
      def whileC[U](test: ⇒ Boolean)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] = {
        if (test) {
          block
          whileC(test)(block)
        }
      }
      @deprecated("Will be removed in Akka 2.1, no replacement.", "2.0.3")
      def repeatC[U](times: Int)(block: ⇒ U @cps[A])(implicit dispatcher: MessageDispatcher, timeout: Timeout): Unit @cps[A] = {
        if (times > 0) {
          block
          repeatC(times - 1)(block)
        }
      }

    }
  }
}

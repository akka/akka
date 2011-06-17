package akka.util

import scala.util.continuations._

package object cps {
  def matchC[A, B, C, D](in: A)(pf: PartialFunction[A, B @cpsParam[C, D]]): B @cpsParam[C, D] = pf(in)

  def loopC[A](block: ⇒ Unit @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.loopC(block)

  def whileC[A](test: ⇒ Boolean)(block: ⇒ Unit @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.whileC(test)(block)

  def repeatC[A](times: Int)(block: ⇒ Unit @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.repeatC(times)(block)
}

package cps {
  object CPSLoop extends DefaultCPSLoop {

    implicit object FutureCPSLoop extends FutureCPSLoop
  }

  trait CPSLoop[A] {
    def loopC(block: ⇒ Unit @cps[A]): Unit @cps[A]
    def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[A]): Unit @cps[A]
    def repeatC(times: Int)(block: ⇒ Unit @cps[A]): Unit @cps[A]
  }

  import akka.dispatch.{ Future, Promise }
  class FutureCPSLoop extends CPSLoop[Future[Any]] {

    def loopC(block: ⇒ Unit @cps[Future[Any]]): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        Future(reify(block) flatMap (_ ⇒ reify(loopC(block))) foreach c)
      }

    def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[Future[Any]]): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        if (test)
          Future(reify(block) flatMap (_ ⇒ reify(whileC(test)(block))) foreach c)
        else
          Promise() completeWithResult (shiftUnitR[Unit, Future[Any]](()) foreach c)
      }

    def repeatC(times: Int)(block: ⇒ Unit @cps[Future[Any]]): Unit @cps[Future[Any]] =
      shift { c: (Unit ⇒ Future[Any]) ⇒
        if (times > 0)
          Future(reify(block) flatMap (_ ⇒ reify(repeatC(times - 1)(block))) foreach c)
        else
          Promise() completeWithResult (shiftUnitR[Unit, Future[Any]](()) foreach c)
      }
  }

  trait DefaultCPSLoop {
    implicit def defaultCPSLoop[A] = new CPSLoop[A] {

      def loopC(block: ⇒ Unit @cps[A]): Unit @cps[A] = {
        block
        loopC(block)
      }

      def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[A]): Unit @cps[A] = {
        if (test) {
          block
          whileC(test)(block)
        }
      }

      def repeatC(times: Int)(block: ⇒ Unit @cps[A]): Unit @cps[A] = {
        if (times > 0) {
          block
          repeatC(times - 1)(block)
        }
      }

    }
  }
}

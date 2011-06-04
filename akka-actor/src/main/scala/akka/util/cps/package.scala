package akka.util

import scala.util.continuations._

package object cps {
  def matchC[A, B, C, D](in: A)(pf: PartialFunction[A, B @cpsParam[C, D]]): B @cpsParam[C, D] = pf(in)

  def loopC[A](block: ⇒ Any @cps[A]): Unit @cps[A] = {
    block
    loopC(block)
  }

  def whileC[A](test: ⇒ Boolean)(block: ⇒ Unit @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.whileC(test)(block)

  def repeatC[A](times: Int)(block: ⇒ Any @cps[A]): Unit @cps[A] = {
    if (times > 0) {
      block
      repeatC(times - 1)(block)
    }
  }
}

package cps {
  object CPSLoop extends DefaultCPSLoop {
    import akka.dispatch.{ Future, Promise }

    implicit val futureCPSLoop: CPSLoop[Future[Any]] = new CPSLoop[Future[Any]] {
      def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[Future[Any]]): Unit @cps[Future[Any]] = {
        shift { c: (Unit ⇒ Future[Any]) ⇒
          Future.flow {
            if (test) {
              block
              Future(()).apply
              whileC(test)(block)
            }
          } flatMap c
        }
      }
    }
  }
  trait CPSLoop[A] {
    def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[A]): Unit @cps[A]
  }
  trait DefaultCPSLoop {
    implicit val defaultCPSLoop = new CPSLoop[Any] {
      def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[Any]): Unit @cps[Any] = {
        if (test) {
          block
          whileC(test)(block)
        }
      }
    }
  }
}

package akka.util

import scala.util.continuations._

package object cps {
  def matchC[A, B, C, D](in: A)(pf: PartialFunction[A, B @cpsParam[C, D]]): B @cpsParam[C, D] = pf(in)

  def loopC[A](block: ⇒ Any @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.loopC(block)

  def whileC[A](test: ⇒ Boolean)(block: ⇒ Unit @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.whileC(test)(block)

  def repeatC[A](times: Int)(block: ⇒ Any @cps[A])(implicit loop: CPSLoop[A]): Unit @cps[A] =
    loop.repeatC(times)(block)
}

package cps {
  object CPSLoop extends DefaultCPSLoop {
    import akka.dispatch.{ Future, Promise }

    implicit val futureCPSLoop: CPSLoop[Future[Any]] = new CPSLoop[Future[Any]] {

      def loopC(block: ⇒ Any @cps[Future[Any]]): Unit @cps[Future[Any]] = {
        shift { c: (Unit ⇒ Future[Any]) ⇒
          Future.flow {
            block
            Future(()).apply
            loopC(block)
          } flatMap c
        }
      }

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

      def repeatC(times: Int)(block: ⇒ Any @cps[Future[Any]]): Unit @cps[Future[Any]] = {
        shift { c: (Unit ⇒ Future[Any]) ⇒
          Future.flow {
            if (times > 0) {
              block
              Future(()).apply
              repeatC(times - 1)(block)
            }
          } flatMap c
        }
      }
    }
  }

  trait CPSLoop[A] {
    def loopC(block: ⇒ Any @cps[A]): Unit @cps[A]
    def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[A]): Unit @cps[A]
    def repeatC(times: Int)(block: ⇒ Any @cps[A]): Unit @cps[A]
  }

  trait DefaultCPSLoop {
    implicit def defaultCPSLoop[A] = new CPSLoop[A] {

      def loopC(block: ⇒ Any @cps[A]): Unit @cps[A] = {
        block
        loopC(block)
      }

      def whileC(test: ⇒ Boolean)(block: ⇒ Unit @cps[A]): Unit @cps[A] = {
        if (test) {
          block
          whileC(test)(block)
        }
      }

      def repeatC(times: Int)(block: ⇒ Any @cps[A]): Unit @cps[A] = {
        if (times > 0) {
          block
          repeatC(times - 1)(block)
        }
      }

    }
  }
}

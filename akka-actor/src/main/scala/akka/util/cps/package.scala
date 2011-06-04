package akka.util

import scala.util.continuations._

package object cps {
  def matchC[A, B, C, D](in: A)(pf: PartialFunction[A, B @cpsParam[C, D]]): B @cpsParam[C, D] = pf(in)

  import TailCalls._

  def loopC[A](block: ⇒ Any @cps[A]): Unit @cps[A] = {
    def f(): TailRec[Unit, A] @cps[A] = {
      block
      Call(() ⇒ f())
    }
    tailcall[Unit, A](f())
  }

  def whileC[A](test: ⇒ Boolean)(block: ⇒ Any @cps[A]): Unit @cps[A] = {
    def f(): TailRec[Unit, A] @cps[A] = {
      if (test) {
        block
        Call(() ⇒ f())
      } else shiftUnit(Return(()))
    }
    tailcall[Unit, A](f())
  }

  def repeatC[A](times: Int)(block: ⇒ Any @cps[A]): Unit @cps[A] = {
    var i = 0
    def f(): TailRec[Unit, A] @cps[A] = {
      if (i < times) {
        i += 1
        block
        Call(() ⇒ f())
      } else shiftUnit(Return(()))
    }
    tailcall[Unit, A](f())
  }

}

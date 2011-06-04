package akka.util.cps

import scala.util.continuations._

object TailCalls {
  sealed trait TailRec[A, B]
  case class Return[A, B](result: A) extends TailRec[A, B]
  case class Call[A, B](thunk: () ⇒ TailRec[A, B] @cps[B]) extends TailRec[A, B]
  def tailcall[A, B](comp: TailRec[A, B]): A @cps[B] = comp match {
    case Call(thunk) ⇒ tailcall(thunk())
    case Return(x)   ⇒ x
  }
}

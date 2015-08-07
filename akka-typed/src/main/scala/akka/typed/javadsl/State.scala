package akka.typed.javadsl

import akka.typed.Behavior
import akka.typed.ActorContext
import akka.typed.Signal
import akka.typed.ScalaDSL
import akka.util.LineNumbers

@SerialVersionUID(1L)
trait State[T] extends Serializable {
  def apply(in: T): T
}

class StateBehavior[T](t: T) extends Behavior[State[T]] {
  def management(ctx: ActorContext[State[T]], msg: Signal): Behavior[State[T]] = {
    ScalaDSL.Same
  }
  def message(ctx: ActorContext[State[T]], msg: State[T]): Behavior[State[T]] = {
    new StateBehavior(msg(t))
  }
}
package akka.streams.impl.ops

import akka.streams.impl._
import akka.streams.Operation.UserOperation

class UserOperationImpl[A, B, S](upstream: Upstream, downstream: Downstream[B], op: UserOperation[A, B, S], batchSize: Int = 1) extends SyncOperation[A] {
  var state = op.seed

  def handleRequestMore(n: Int): Effect = upstream.requestMore(batchSize)
  def handleCancel(): Effect = upstream.cancel

  def handleNext(element: A): Effect = handleCommand(op.onNext(state, element)) ~ upstream.requestMore(1)

  import UserOperation.{ Continue ⇒ _, _ }
  def handleCommand(command: Command[B, S]): Effect =
    command match {
      case UserOperation.Continue(newState) ⇒
        state = newState; Continue
      case Stop           ⇒ downstream.complete ~ upstream.cancel
      case Emit(value)    ⇒ downstream.next(value)
      case Commands(cmds) ⇒ cmds.map(handleCommand).reduce(_ ~ _)
    }

  def handleComplete(): Effect = op.onComplete(state).foldLeft(Continue: Effect)((e, element) ⇒ e ~ downstream.next(element))
  def handleError(cause: Throwable): Effect = downstream.error(cause)
}

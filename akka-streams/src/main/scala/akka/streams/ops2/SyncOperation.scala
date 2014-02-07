package akka.streams.ops2

import akka.streams.Operation.{ Sink, Source }

sealed trait Result[+O] {
  def ~[O2 >: O](next: Result[O2]): Result[O2] =
    if (next == Continue) this
    else CombinedResult(Vector(this, next))
}
object Result {
  def step[O](body: ⇒ Result[_]): Result[O] = new Step[O] {
    def run(): Result[_] = body
  }
  def sideEffect[O](body: ⇒ Unit): Result[O] = new SideEffect[O] {
    def runSideEffect(): Unit = body
  }
}
object Continue extends Result[Nothing] {
  override def ~[O2 >: Nothing](next: Result[O2]): Result[O2] = next
}
case class CombinedResult[+O](results: Vector[Result[O]]) extends Result[O] {
  override def ~[O2 >: O](next: Result[O2]): Result[O2] =
    if (next == Continue) this
    else CombinedResult(results :+ next)
}
trait Step[+O] extends Result[O] {
  def run(): Result[_]
}
trait SideEffect[+O] extends Result[O] {
  def runSideEffect(): Unit
}
abstract class SideEffectImpl[O](body: ⇒ Unit) extends SideEffect[O] {
  def runSideEffect(): Unit = body
}

trait Upstream {
  val requestMore: Int ⇒ Result[Nothing]
  val cancel: Result[Nothing]
}
trait Downstream[O] {
  val next: O ⇒ Result[O]
  val complete: Result[Nothing]
  val error: Throwable ⇒ Result[O]
}

trait SyncSource[+O] {
  def handleRequestMore(n: Int): Result[O]
  def handleCancel(): Result[O]
}
trait SyncSink[-I, +O] {
  def handleNext(element: I): Result[O]
  def handleComplete(): Result[O]
  def handleError(cause: Throwable): Result[O]
}

trait SyncOperation[-I, +O] extends SyncSource[O] with SyncSink[I, O]

trait DynamicSyncOperation[I, O] extends SyncOperation[I, O] {
  type State = SyncOperation[I, O]
  private[this] var state: State = initial

  def initial: State
  def become(nextState: State): Unit = state = nextState

  def handleRequestMore(n: Int): Result[O] = state.handleRequestMore(n)
  def handleCancel(): Result[O] = state.handleCancel()

  def handleNext(element: I): Result[O] = state.handleNext(element)
  def handleComplete(): Result[O] = state.handleComplete()
  def handleError(cause: Throwable): Result[O] = state.handleError(cause)
}

trait Subscribable {
  def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O, O], Result[O])): Result[O]
}

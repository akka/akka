package akka.streams.ops2

import akka.streams.Operation.{ Sink, Source }

sealed trait Result {
  def ~(next: Result): Result =
    if (next == Continue) this
    else CombinedResult(Vector(this, next))
}
object Result {
  def step[O](body: ⇒ Result): Result = new Step {
    def run(): Result = body
  }
  def sideEffect[O](body: ⇒ Unit): Result = new SideEffect {
    def runSideEffect(): Unit = body
  }
}
object Continue extends Result {
  override def ~(next: Result): Result = next
}
case class CombinedResult(results: Vector[Result]) extends Result {
  override def ~(next: Result): Result =
    if (next == Continue) this
    else CombinedResult(results :+ next)
}
trait Step extends Result {
  def run(): Result
}
trait SideEffect extends Result {
  def runSideEffect(): Unit
}
abstract class SideEffectImpl(body: ⇒ Unit) extends SideEffect {
  def runSideEffect(): Unit = body
}

trait Upstream {
  val requestMore: Int ⇒ Result
  val cancel: Result
}
trait Downstream[O] {
  val next: O ⇒ Result
  val complete: Result
  val error: Throwable ⇒ Result
}

trait SyncSource {
  def handleRequestMore(n: Int): Result
  def handleCancel(): Result
}
trait SyncRunnable {
  // should we call this onSubscribe instead?
  def start(): Result = Continue
}
trait SyncSink[-I] extends SyncRunnable {
  def handleNext(element: I): Result
  def handleComplete(): Result
  def handleError(cause: Throwable): Result
}

trait SyncOperation[-I] extends SyncSource with SyncSink[I]

trait DynamicSyncSource extends SyncSource {
  type State = SyncSource
  private[this] var state: State = initial

  def initial: State
  def become(nextState: State): Unit = state = nextState

  def handleRequestMore(n: Int): Result = state.handleRequestMore(n)
  def handleCancel(): Result = state.handleCancel()
}

trait DynamicSyncOperation[I] extends SyncOperation[I] {
  type State = SyncOperation[I]
  private[this] var state: State = initial

  def initial: State
  def become(nextState: State): Unit = state = nextState

  def handleRequestMore(n: Int): Result = state.handleRequestMore(n)
  def handleCancel(): Result = state.handleCancel()

  def handleNext(element: I): Result = state.handleNext(element)
  def handleComplete(): Result = state.handleComplete()
  def handleError(cause: Throwable): Result = state.handleError(cause)
}

trait Subscribable {
  def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O], Result)): Result
  def subscribeFrom[O](sink: Sink[O])(onSubscribe: Downstream[O] ⇒ (SyncSource, Result)): Result = ???
}

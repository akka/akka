package akka.streams.ops2

import akka.streams.Operation.{ Sink, Source }

/** Constructors for upstream effects */
trait Upstream {
  val requestMore: Int ⇒ Effect
  val cancel: Effect
}

/** Constructors for downstream effects */
trait Downstream[O] {
  val next: O ⇒ Effect
  val complete: Effect
  val error: Throwable ⇒ Effect
}

/** The interface an implementation a synchronous source implementation has to implement */
trait SyncSource {
  def handleRequestMore(n: Int): Effect
  def handleCancel(): Effect
}

/**
 * Pipeline implementations and Sinks need a signal to start their work. This is needed because
 * internal subscriptions are not established from the outside but from inside a sink
 * implementation.
 */
trait SyncRunnable {
  def start(): Effect = Continue
}

/** The interface an implementation a synchronous sink implementation has to implement */
trait SyncSink[-I] extends SyncRunnable {
  def handleNext(element: I): Effect
  def handleComplete(): Effect
  def handleError(cause: Throwable): Effect
}

/** The interface an implementation a synchronous operation implementation has to implement */
trait SyncOperation[-I] extends SyncSource with SyncSink[I]

/**
 *  An abstract SyncSource implementation to be used as a base for SyncSource implementations
 *  that implement their behavior as a state machine.
 */
abstract class DynamicSyncSource extends SyncSource {
  type State = SyncSource
  private[this] var state: State = initial

  def initial: State
  def become(nextState: State): Unit = state = nextState

  def handleRequestMore(n: Int): Effect = state.handleRequestMore(n)
  def handleCancel(): Effect = state.handleCancel()
}

/**
 *  An abstract SyncOperation implementation to be used as a base for SyncOperation implementations
 *  that implement their behavior as a state machine.
 */
abstract class DynamicSyncOperation[I] extends SyncOperation[I] {
  type State = SyncOperation[I]
  private[this] var state: State = initial

  def initial: State
  def become(nextState: State): Unit = state = nextState

  def handleRequestMore(n: Int): Effect = state.handleRequestMore(n)
  def handleCancel(): Effect = state.handleCancel()

  def handleNext(element: I): Effect = state.handleNext(element)
  def handleComplete(): Effect = state.handleComplete()
  def handleError(cause: Throwable): Effect = state.handleError(cause)
}

/**
 * Additional Effects supplied by the context to allow additional executing additional effects
 * of link internal sources and sinks.
 */
trait ContextEffects {
  def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O], Effect)): Effect
  def subscribeFrom[O](sink: Sink[O])(onSubscribe: Downstream[O] ⇒ (SyncSource, Effect)): Effect
}

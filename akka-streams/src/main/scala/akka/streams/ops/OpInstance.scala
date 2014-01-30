package akka.streams.ops

import rx.async.api.Producer
import rx.async.spi.Publisher

trait OpInstance[-I, +O] {
  def handle(result: SimpleResult[I]): Result[O]

  // convenience method which calls handle for all elements and combines the results
  // (which obviously isn't as performant than a direct handling
  // FIXME: remove later and implement properly everywhere
  def handleMany(e: EmitMany[I]): Result[O] =
    e.elements.map(e ⇒ handle(Emit(e))).reduceLeftOption(_ ~ _).getOrElse(Continue)
}

/* The result of a single calculation step */
sealed trait Result[+O] {
  def ~[O2 >: O](other: Result[O2]): Result[O2] =
    if (other == Continue) this
    else Combine(this, other)
}
sealed trait SimpleResult[+O] extends Result[O]
sealed trait ForwardResult[+O] extends SimpleResult[O]
sealed trait BackchannelResult extends SimpleResult[Nothing]

// SEVERAL RESULTS
// TODO: Make sure we have no combinations of the form Combine(x, Combine())
//       (or at least no deep ones) as they may potentially lead to StackOverflows in the stepper
case class Combine[O](first: Result[O], second: Result[O]) extends Result[O]
// TODO: consider introducing a `ForwardCombine` type tagging purely
//       forward going combinations to avoid the stepper for simple
//       operation combinations

// NOOP
case object Continue extends Result[Nothing] {
  override def ~[O2 >: Nothing](other: Result[O2]): Result[O2] = other
}
// Definitions for internal publishers that don't have to be wired completely if they
// don't leave the scope of this execution
case class InternalPublisherTemplate[O](f: SubscriptionResults ⇒ PublisherResults[O] ⇒ PublisherHandler[O]) extends Producer[O] {
  def getPublisher: Publisher[O] = ???
}
// FIXME: What happens if this one escapes scope? Do we provide proper hooks to get it properly connected?
//        Is this expected?
case class InternalPublisherFinished[O](f: PublisherResults[O] ⇒ PublisherHandler[O]) extends Producer[O] {
  def getPublisher: Publisher[O] = ???
}

// FORWARD
case class Emit[+O](t: O) extends ForwardResult[O]
case class EmitMany[+O](elements: Vector[O]) extends ForwardResult[O] {
  require(elements.size > 1, "don't use EmitMany to emit only one element")
}
case object Complete extends ForwardResult[Nothing]
case class Error(cause: Throwable) extends ForwardResult[Nothing]

case class Subscribe[T, U](producer: Producer[T])(val handlerFactory: SubscriptionResults ⇒ SubscriptionHandler[T, U]) extends ForwardResult[U]

// BACKCHANNEL
case class RequestMore(n: Int) extends BackchannelResult {
  require(n > 0)
}

// CUSTOM
private[streams] trait CustomForwardResult[+O] extends ForwardResult[O]
private[streams] trait CustomBackchannelResult extends BackchannelResult

trait SubscriptionHandler[-I, +O] {
  def handle(result: ForwardResult[I]): Result[O]
  def initial: Result[O]
}
trait SubscriptionResults {
  def requestMore(n: Int): Result[Nothing]
}

trait PublisherHandler[O] {
  def handle(result: BackchannelResult): Result[Producer[O]]
}
trait PublisherResults[O] {
  def emit(o: O): Result[Producer[O]]
  def complete: Result[Producer[O]]
  def error(cause: Throwable): Result[Producer[O]]
}

/** A helper trait for an OpInstance that can change its behaviour */
trait OpInstanceStateMachine[I, O] extends OpInstance[I, O] {
  type State = SimpleResult[I] ⇒ Result[O]
  def initialState: State
  var state: State = initialState

  def handle(result: SimpleResult[I]): Result[O] = state(result)

  def become(newState: State): Unit = state = newState
}

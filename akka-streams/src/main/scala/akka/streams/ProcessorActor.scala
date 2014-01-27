package akka.streams

import rx.async.api.{ Producer, Processor }
import rx.async.spi.Subscription

object ProcessorActor {
  // TODO: needs settings
  def processor[I, O](operations: Operation[I, O]): Processor[I, O] = ???

  sealed trait Result[+O] {
    def andThen[O2](next: OpInstance[O, O2]): Result[O2]
  }
  case class Emit[O](t: O) extends Result[O] {
    def andThen[O2](next: OpInstance[O, O2]): Result[O2] = next.onNext(t)
  }
  case object Continue extends Result[Nothing] {
    def andThen[O2](next: OpInstance[Nothing, O2]): Result[O2] = this
  }
  case class EmitLast[O](i: O) extends Result[O] {
    def andThen[O2](next: OpInstance[O, O2]): Result[O2] = ???
  }
  case object Complete extends Result[Nothing] {
    def andThen[O2](next: OpInstance[Nothing, O2]): Result[O2] = next.onComplete()
  }
  case class Error(cause: Throwable) extends Result[Nothing] {
    def andThen[O2](next: OpInstance[Nothing, O2]): Result[O2] = next.onError(cause)
  }
  case class Subscribe[T, U](producer: Producer[T])(handler: OpInstance[T, T] ⇒ OpInstance[T, U]) extends Result[U] {
    def andThen[O2](next: OpInstance[U, O2]): Result[O2] = ???
  }

  trait OpInstance[-I, +O] {
    def requestMore(n: Int): Int
    def onNext(i: I): Result[O]
    def onComplete(): Result[O]
    def onError(cause: Throwable): Result[O]
  }

  trait OpInstanceStateMachine[I, O] extends OpInstance[I, O] {
    type State = OpInstance[I, O]
    def initialState: State
    var state: State = initialState

    def requestMore(n: Int): Int = state.requestMore(n)
    def onNext(i: I): Result[O] = state.onNext(i)
    def onComplete(): Result[O] = state.onComplete()
    def onError(cause: Throwable): Result[O] = state.onError(cause)

    def become(newState: State): Unit = state = newState
  }

  def instantiate[I, O](operation: Operation[I, O]): OpInstance[I, O] = operation match {
    case AndThen(first, second) ⇒
      new OpInstance[I, O] {
        val firstI = instantiate(first)
        val secondI = instantiate(second)
        def requestMore(n: Int): Int = firstI.requestMore(secondI.requestMore(n))
        def onNext(i: I): Result[O] = firstI.onNext(i).andThen(secondI)
        def onComplete(): Result[O] = firstI.onComplete().andThen(secondI)
        def onError(cause: Throwable): Result[O] = firstI.onError(cause).andThen(secondI)
      }
    case Map(f) ⇒
      new OpInstance[I, O] {
        def requestMore(n: Int): Int = n
        def onNext(i: I): Result[O] = Emit(f(i)) // FIXME: error handling
        def onComplete(): Result[O] = Complete
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
    case Fold(seed, acc) ⇒
      new OpInstance[I, O] {
        var z = seed
        def requestMore(n: Int): Int =
          // we can instantly consume all values, even if there's just one requested,
          // though we probably need to be careful with MaxValue which may lead to
          // overflows easily
          Int.MaxValue
        def onNext(i: I): Result[O] = {
          z = acc(seed, i) // FIXME: error handling
          Continue
        }
        def onComplete(): Result[O] = EmitLast(z)
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
    case Filter(pred) ⇒
      new OpInstance[I, O] {
        def requestMore(n: Int): Int = n /* + heuristic of previously filtered? */
        def onNext(i: I): Result[O] = if (pred(i)) Emit(i.asInstanceOf[O]) else Continue /* and request one more */ // FIXME: error handling
        def onComplete(): Result[O] = Complete
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
    case FlatMap(f) ⇒
      // two different kinds of flatMap: merge and concat, in RxJava: `flatMap` which merges and `concatMap` which concats
      val shouldMerge = true
      if (shouldMerge) /* merge */
        new OpInstance[I, O] {
          def requestMore(n: Int): Int =
            ???
          // we need to subscribe and read the complete stream of inner Producer[O]
          // and then always subscribe and keep one element buffered / requested
          // and then on request deliver those in the sequence they were received
          def onNext(i: I): Result[O] = ???
          def onComplete(): Result[O] = ???
          def onError(cause: Throwable): Result[O] = ???
        }
      else /* concat */
        new OpInstanceStateMachine[I, O] {
          def initialState = Waiting

          def Waiting = new State {
            def requestMore(n: Int): Int = {
              become(WaitingForElement(n))
              1
            }
            def onNext(i: I): Result[O] = throw new IllegalStateException("No element requested")
            def onComplete(): Result[O] = Complete
            def onError(cause: Throwable): Result[O] = Error(cause)
          }
          def WaitingForElement(remaining: Int): State = new State {
            def requestMore(n: Int): Int = {
              become(WaitingForElement(remaining + n))
              0 // we already requested one parent element
            }
            def onNext(i: I): Result[O] =
              Subscribe(f(i)) { subscription ⇒ // TODO: handleErrors
                val handler = ReadSubstream(subscription, remaining)
                become(handler)
                handler.subHandler
              }
            def onComplete(): Result[O] = Complete
            def onError(cause: Throwable): Result[O] = Error(cause)
          }
          case class ReadSubstream(subscription: OpInstance[O, O], remaining: Int) extends State {
            // invariant: subscription.requestMore has been called for every
            // of the requested elements
            // each emitted element decrements that counter by one
            var curRemaining = remaining
            var closeAtEnd = false
            def requestMore(n: Int): Int = {
              curRemaining += n
              subscription.requestMore(n)
              0 // from the parent stream
            }
            def onNext(i: I): Result[O] = throw new IllegalStateException("No element requested")
            def onComplete(): Result[O] = { closeAtEnd = true; Continue }
            def onError(cause: Throwable): Result[O] =
              // shortcut close result stream?
              // see RxJava `mapManyDelayError`
              ???

            def subHandler = new OpInstance[O, O] {
              def requestMore(n: Int): Int = ??? // who would ever call this? FIXME: better model that doesn't rely on this
              def onNext(i: O): Result[O] = {
                curRemaining -= 1
                Emit(i)
              }
              def onComplete(): Result[O] =
                if (curRemaining > 0) {
                  // FIXME: request one more from parent stream
                  // but how?
                  become(WaitingForElement(curRemaining))
                  Continue
                } else {
                  become(Waiting)
                  Continue
                }
              def onError(cause: Throwable): Result[O] = Error(cause)
            }
          }
        }
    case FoldUntil(seed, acc) ⇒
      new OpInstance[I, O] {
        var z = seed
        def requestMore(n: Int): Int = ???
        def onNext(i: I): Result[O] =
          acc(z, i) match {
            case FoldResult.Emit(value, nextSeed) ⇒
              z = nextSeed; Emit(value)
            case FoldResult.Continue(newZ) ⇒ z = newZ; Continue
          }
        def onComplete(): Result[O] =
          // TODO: could also define that the latest seed should be returned, or flag an error
          //       if the last element doesn't match the predicate
          Complete
        def onError(cause: Throwable): Result[O] = Error(cause)
      }

    case Span(pred) ⇒
      new OpInstance[I, O] {
        def requestMore(n: Int): Int = n
        def onNext(i: I): Result[O] = {
          // question is here where to dispatch to and how to access those:
          //  - parent stream
          //  - substream
          ???
        }
        def onComplete(): Result[O] = ???
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
  }
}

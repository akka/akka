package akka.streams

import rx.async.api.{ Producer, Processor }
import scala.annotation.tailrec

object ProcessorActor {
  // TODO: needs settings
  //def processor[I, O](operations: Operation[I, O]): Processor[I, O] = ???

  sealed trait Result[+O] {
    def ~[O2 >: O](other: Result[O2]): Result[O2] =
      if (other == Continue) this
      else Combine(this, other)
  }
  sealed trait ForwardResult[+O] extends Result[O]
  sealed trait BackchannelResult[+O] extends Result[O]
  case class Combine[O](first: Result[O], second: Result[O]) extends Result[O]
  case object Continue extends Result[Nothing] {
    override def ~[O2 >: Nothing](other: Result[O2]): Result[O2] = other
  }

  // FORWARD
  case class Emit[O](t: O) extends ForwardResult[O]
  // optimization: case class EmitMany[O](t: Seq[O]) extends Result[O]
  case class EmitLast[O](i: O) extends ForwardResult[O]
  case object Complete extends ForwardResult[Nothing]
  case class Error(cause: Throwable) extends ForwardResult[Nothing]

  case class Subscribe[T, U](producer: Producer[T])(handler: SubscriptionResults ⇒ SubscriptionHandler[T, U]) extends ForwardResult[U]
  case class EmitProducer[O](f: PublisherResults[O] ⇒ PublisherHandler[O]) extends ForwardResult[O]

  // BACKCHANNEL
  case class RequestMoreFromNext(n: Int) extends BackchannelResult[Nothing]

  trait SubscriptionHandler[-I, +O] {
    def onNext(i: I): Result[O]
    def onComplete(): Result[O]
    def onError(cause: Throwable): Result[O]
  }
  trait SubscriptionResults {
    def requestMore(n: Int): Result[Nothing]
  }

  trait PublisherHandler[+O] {
    def requestMoreResult(n: Int): Result[O]
  }
  trait PublisherResults[O] {
    def emit(o: O): Result[O]
    def complete: Result[O]
    def error(cause: Throwable): Result[O]
  }

  trait OpInstance[-I, +O] extends SubscriptionHandler[I, O] with PublisherHandler[O]
  trait SimpleOpInstance[-I, +O] extends OpInstance[I, O] {
    def requestMoreResult(n: Int): Result[O] = RequestMoreFromNext(requestMore(n))
    def requestMore(n: Int): Int
  }

  trait OpInstanceStateMachine[I, O] extends OpInstance[I, O] {
    type State = SimpleOpInstance[I, O]
    def initialState: State
    var state: State = initialState

    def requestMoreResult(n: Int): Result[O] = state.requestMoreResult(n)
    def onNext(i: I): Result[O] = state.onNext(i)
    def onComplete(): Result[O] = state.onComplete()
    def onError(cause: Throwable): Result[O] = state.onError(cause)

    def become(newState: State): Unit = state = newState
  }

  def andThenInstance[I1, I2, O](andThen: AndThen[I1, I2, O]): OpInstance[I1, O] =
    new OpInstance[I1, O] {
      val firstI = instantiate(andThen.first)
      val secondI = instantiate(andThen.second)

      def requestMoreResult(n: Int): Result[O] =
        handleSecondResult(secondI.requestMoreResult(n))

      def onNext(i: I1): Result[O] = handleFirstResult(firstI.onNext(i))
      def onComplete(): Result[O] = handleFirstResult(firstI.onComplete())
      def onError(cause: Throwable): Result[O] = handleFirstResult(firstI.onError(cause))

      sealed trait Calc
      sealed trait SimpleCalc extends Calc
      case class CombinedResult(first: Calc, second: Calc) extends Calc
      case class HalfFinished(first: Result[O], second: Calc) extends Calc

      case class FirstResult(result: Result[I2]) extends SimpleCalc
      case class SecondResult(result: Result[O]) extends SimpleCalc
      case class Finished(result: Result[O]) extends SimpleCalc

      // these two are short-cutting entry-points that handle common non-trampolining cases
      // before calling the stepper (which must contains the same logic once again)
      def handleFirstResult(res: Result[I2]): Result[O] = res match {
        case Continue                 ⇒ Continue
        case b: BackchannelResult[I2] ⇒ b.asInstanceOf[Result[O]]
        case Combine(a, b)            ⇒ run(CombinedResult(FirstResult(a), FirstResult(b)))
        case x                        ⇒ run(FirstResult(x))
      }
      def handleSecondResult(res: Result[O]): Result[O] = res match {
        case Continue            ⇒ Continue
        case f: ForwardResult[O] ⇒ f
        case Combine(a, b)       ⇒ run(CombinedResult(SecondResult(a), SecondResult(b)))
        case x                   ⇒ run(SecondResult(x))
      }

      def firstResult(res: Result[I2]): SimpleCalc = res match {
        case Continue ⇒ Finished(Continue)
        case x        ⇒ FirstResult(x)
      }
      def secondResult(res: Result[O]): SimpleCalc = res match {
        case Continue ⇒ Finished(Continue)
        case x        ⇒ SecondResult(x)
      }
      def runSimpleStep(calc: SimpleCalc): SimpleCalc = calc match {
        case SecondResult(RequestMoreFromNext(n)) ⇒ firstResult(firstI.requestMoreResult(n))
        case SecondResult(x: ForwardResult[_])    ⇒ Finished(x)
        case SecondResult(Continue)               ⇒ Finished(Continue)
        case FirstResult(Emit(i))                 ⇒ secondResult(secondI.onNext(i))
        case FirstResult(EmitLast(i))             ⇒ secondResult(secondI.onNext(i) ~ secondI.onComplete())
        case FirstResult(Complete)                ⇒ secondResult(secondI.onComplete())
        case FirstResult(Error(cause))            ⇒ secondResult(secondI.onError(cause))
        case FirstResult(r: RequestMoreFromNext)  ⇒ Finished(r)
        case FirstResult(Continue)                ⇒ Finished(Continue)
        // what does this line mean exactly? why is it valid?
        case FirstResult(x: BackchannelResult[_]) ⇒ Finished(x.asInstanceOf[Result[O]])
      }
      def runOneStep(calc: Calc): Calc =
        calc match {
          case SecondResult(Combine(a, b)) ⇒
            //CombinedResult(SecondResult(a), SecondResult(b))
            // inline "case CombinedResult" for one less round-trip
            runOneStep(SecondResult(a)) match {
              case Finished(Continue) ⇒ SecondResult(b)
              case Finished(aRes)     ⇒ HalfFinished(aRes, SecondResult(b))
              case x                  ⇒ CombinedResult(x, SecondResult(b))
            }
          case FirstResult(Combine(a, b)) ⇒
            //CombinedResult(FirstResult(a), FirstResult(b))
            // inline "case CombinedResult" for one less round-trip
            runOneStep(FirstResult(a)) match {
              case Finished(Continue) ⇒ FirstResult(b)
              case Finished(aRes)     ⇒ HalfFinished(aRes, FirstResult(b))
              case x                  ⇒ CombinedResult(x, FirstResult(b))
            }
          case s: SimpleCalc ⇒ runSimpleStep(s)
          case CombinedResult(a, b) ⇒
            runOneStep(a) match {
              case Finished(Continue) ⇒ b
              case Finished(aRes)     ⇒ HalfFinished(aRes, b)
              case x                  ⇒ CombinedResult(x, b)
            }
          case HalfFinished(aRes, b) ⇒
            runOneStep(b) match {
              case Finished(Continue) ⇒ Finished(aRes)
              case Finished(bRes)     ⇒ Finished(aRes ~ bRes)
              case x                  ⇒ HalfFinished(aRes, x)
            }
        }

      @tailrec def run(calc: Calc): Result[O] = {
        val res = runOneStep(calc)
        //println(s"$calc => $res")
        res match {
          case Finished(res) ⇒ res
          case x             ⇒ run(x)
        }
      }
    }

  def instantiate[I, O](operation: Operation[I, O]): OpInstance[I, O] = operation match {
    case andThen: AndThen[_, _, _] ⇒ andThenInstance(andThen)
    case Map(f) ⇒
      new SimpleOpInstance[I, O] {
        def requestMore(n: Int): Int = n
        def onNext(i: I): Result[O] = Emit(f(i)) // FIXME: error handling
        def onComplete(): Result[O] = Complete
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
    case Fold(seed, acc) ⇒
      new SimpleOpInstance[I, O] {
        val batchSize = 100
        var missing = 0
        var z = seed
        def requestMore(n: Int): Int = {
          // we can instantly consume all values, even if there's just one requested,
          // though we probably need to be careful with MaxValue which may lead to
          // overflows easily

          missing = batchSize
          batchSize
        }
        def onNext(i: I): Result[O] = {
          z = acc(z, i) // FIXME: error handling
          missing -= 1
          if (missing == 0) { missing = batchSize; RequestMoreFromNext(batchSize) }
          else Continue
        }
        def onComplete(): Result[O] = EmitLast(z)
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
    case Filter(pred) ⇒
      new SimpleOpInstance[I, O] {
        def requestMore(n: Int): Int = n /* + heuristic of previously filtered? */
        def onNext(i: I): Result[O] = if (pred(i)) Emit(i.asInstanceOf[O]) else Continue /* and request one more */ // FIXME: error handling
        def onComplete(): Result[O] = Complete
        def onError(cause: Throwable): Result[O] = Error(cause)
      }
    /*case Flatten() =>
      val shouldMerge = false*/
    case FlatMap(f) ⇒
      // two different kinds of flatMap: merge and concat, in RxJava: `flatMap` which merges and `concatMap` which concats
      val shouldMerge = false
      if (shouldMerge) /* merge */
        // we need to subscribe and read the complete stream of inner Producer[O]
        // and then always subscribe and keep one element buffered / requested
        // and then on request deliver those in the sequence they were received
        ???
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
          case class ReadSubstream(subscription: SubscriptionResults, remaining: Int) extends State {
            // invariant: subscription.requestMore has been called for every
            // of the requested elements
            // each emitted element decrements that counter by one
            var curRemaining = remaining
            var closeAtEnd = false

            def requestMore(n: Int): Int = ???
            override def requestMoreResult(n: Int): Result[O] = {
              curRemaining += n
              subscription.requestMore(n)
            }
            def onNext(i: I): Result[O] = throw new IllegalStateException("No element requested")
            def onComplete(): Result[O] = { closeAtEnd = true; Continue }
            def onError(cause: Throwable): Result[O] =
              // shortcut close result stream?
              // see RxJava `mapManyDelayError`
              ???

            def subHandler = new SimpleOpInstance[O, O] {
              def requestMore(n: Int): Int = ??? // who would ever call this? FIXME: better model that doesn't rely on this
              def onNext(i: O): Result[O] = {
                curRemaining -= 1
                Emit(i)
              }
              def onComplete(): Result[O] =
                if (curRemaining > 0) {
                  // FIXME: request one more element from parent stream
                  // but how?
                  become(WaitingForElement(curRemaining))
                  RequestMoreFromNext(1)
                } else {
                  become(Waiting)
                  Continue
                }
              def onError(cause: Throwable): Result[O] = Error(cause)
            }
          }
        }
    case FoldUntil(seed, acc) ⇒
      new SimpleOpInstance[I, O] {
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
      new SimpleOpInstance[I, O] {
        var curPublisher: PublisherResults[O] = _

        def requestMore(n: Int): Int = n
        def onNext(i: I): Result[O] = {
          // question is here where to dispatch to and how to access those:
          //  - parent stream
          //  - substream
          val res =
            if (curPublisher eq null)
              EmitProducer[O] { publisher ⇒
                curPublisher = publisher

                new PublisherHandler[O] {
                  var cachedFirst = i
                  def requestMore(n: Int): Int = ???

                  override def requestMoreResult(n: Int): Result[O] =
                    RequestMoreFromNext(n)
                  // if (cachedFirst ne null) ~ Emit(cachedFirst)
                }
              }
            else
              curPublisher.emit(i.asInstanceOf[O])
          if (pred(i)) curPublisher = null
          res
        }
        def onComplete(): Result[O] = {
          Complete
          // ~ curPublisher.complete
        }
        def onError(cause: Throwable): Result[O] = {
          Error(cause) // ~ curPublisher.error(cause)
        }
      }
    case Produce(iterable) ⇒
      new OpInstance[I, O] {
        val it = iterable.iterator
        override def requestMoreResult(n: Int): Result[O] =
          if (n > 0) {
            if (it.hasNext) Emit(it.next()) ~ requestMoreResult(n - 1)
            else Complete
          } else Continue

        def onNext(i: I): Result[O] = ???
        def onComplete(): Result[O] = ???
        def onError(cause: Throwable): Result[O] = ???
      }
  }
}

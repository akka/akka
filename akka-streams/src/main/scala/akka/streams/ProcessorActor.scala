package akka.streams

import rx.async.api.Producer
import scala.annotation.tailrec
import scala.collection.immutable.VectorBuilder

object ProcessorActor {
  // TODO: needs settings
  //def processor[I, O](operations: Operation[I, O]): Processor[I, O] = ???

  /* The result of a calculation step */
  sealed trait Result[+O] {
    def ~[O2 >: O](other: Result[O2]): Result[O2] =
      if (other == Continue) this
      else Combine(this, other)
  }
  sealed trait ForwardResult[+O] extends Result[O]
  sealed trait BackchannelResult extends Result[Nothing]

  // SEVERAL RESULTS
  case class Combine[O](first: Result[O], second: Result[O]) extends Result[O]
  // TODO: consider introducing a `ForwardCombine` type tagging purely
  //       forward going combinations to avoid the stepper for simple
  //       operation combinations

  // NOOP
  case object Continue extends Result[Nothing] {
    override def ~[O2 >: Nothing](other: Result[O2]): Result[O2] = other
  }

  // FORWARD
  case class Emit[O](t: O) extends ForwardResult[O]
  case class EmitMany[O](t: Vector[O]) extends ForwardResult[O]
  case object Complete extends ForwardResult[Nothing]
  case class Error(cause: Throwable) extends ForwardResult[Nothing]

  case class Subscribe[T, U](producer: Producer[T])(handler: SubscriptionResults ⇒ SubscriptionHandler[T, U]) extends ForwardResult[U]
  case class EmitProducer[O](f: PublisherResults[O] ⇒ PublisherHandler[O]) extends ForwardResult[O]

  // BACKCHANNEL
  case class RequestMore(n: Int) extends BackchannelResult

  trait SubscriptionHandler[-I, +O] {
    def handle(result: ForwardResult[I]): Result[O]
  }
  trait SimpleSubscriptionHandler[-I, +O] extends SubscriptionHandler[I, O] {
    def handle(result: ForwardResult[I]): Result[O] = result match {
      case Emit(i)      ⇒ onNext(i)
      case EmitMany(it) ⇒ it.map(onNext).reduceLeftOption(_ ~ _).getOrElse(Continue)
      case Complete     ⇒ onComplete()
      case Error(cause) ⇒ onError(cause)
    }

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
  trait SimplePublisherHandler[+O] extends PublisherHandler[O] {
    def requestMoreResult(n: Int): Result[O] = RequestMore(requestMore(n))
    def requestMore(n: Int): Int
  }
  trait PublisherResults[O] {
    def emit(o: O): Result[O]
    def complete: Result[O]
    def error(cause: Throwable): Result[O]
  }

  trait OpInstance[-I, +O] extends SubscriptionHandler[I, O] with PublisherHandler[O]
  trait SimpleOpInstance[-I, +O] extends OpInstance[I, O] with SimpleSubscriptionHandler[I, O] with SimplePublisherHandler[O]

  trait OpInstanceStateMachine[I, O] extends OpInstance[I, O] {
    type State = SimpleOpInstance[I, O]
    def initialState: State
    var state: State = initialState

    def requestMoreResult(n: Int): Result[O] = state.requestMoreResult(n)
    def handle(result: ForwardResult[I]): Result[O] = state.handle(result)

    def become(newState: State): Unit = state = newState
  }

  def andThenInstance[I1, I2, O](andThen: AndThen[I1, I2, O]): OpInstance[I1, O] =
    new OpInstance[I1, O] {
      val firstI = instantiate(andThen.first)
      val secondI = instantiate(andThen.second)

      def requestMoreResult(n: Int): Result[O] =
        handleSecondResult(secondI.requestMoreResult(n))

      def handle(result: ForwardResult[I1]): Result[O] = handleFirstResult(firstI.handle(result))

      sealed trait Calc
      sealed trait SimpleCalc extends Calc
      case class CombinedResult(first: Calc, second: Calc) extends Calc
      case class HalfFinished(first: Result[O], second: Calc) extends Calc

      case class FirstResult(result: Result[I2]) extends SimpleCalc
      case class SecondResult(result: Result[O]) extends SimpleCalc
      case class Finished(result: Result[O]) extends SimpleCalc
      // TODO: add constant for Finished(Continue)

      // these two are short-cutting entry-points that handle common non-trampolining cases
      // before calling the stepper (which must contains the same logic once again)
      // This way we can avoid the wrapping cost of trampolining in many cases.
      def handleFirstResult(res: Result[I2]): Result[O] = res match {
        case Continue             ⇒ Continue
        case b: BackchannelResult ⇒ b
        case Combine(a, b)        ⇒ run(CombinedResult(FirstResult(a), FirstResult(b)))
        case x                    ⇒ run(FirstResult(x))
      }
      def handleSecondResult(res: Result[O]): Result[O] = res match {
        case Continue            ⇒ Continue
        case f: ForwardResult[O] ⇒ f
        case Combine(a, b)       ⇒ run(CombinedResult(SecondResult(a), SecondResult(b)))
        case x                   ⇒ run(SecondResult(x))
      }

      @tailrec def run(calc: Calc): Result[O] = {
        val res = runOneStep(calc)
        //println(s"$calc => $res")
        res match {
          case Finished(res) ⇒ res
          case x             ⇒ run(x)
        }
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

      def runSimpleStep(calc: SimpleCalc): SimpleCalc = calc match {
        case SecondResult(RequestMore(n)) ⇒ firstResult(firstI.requestMoreResult(n))
        case SecondResult(x: ForwardResult[_])    ⇒ Finished(x)
        case SecondResult(Continue)               ⇒ Finished(Continue)
        case FirstResult(f: ForwardResult[_])     ⇒ secondResult(secondI.handle(f))
        case FirstResult(r: RequestMore)  ⇒ Finished(r)
        case FirstResult(Continue)                ⇒ Finished(Continue)
        case FirstResult(x: BackchannelResult)    ⇒ Finished(x)
      }

      // these are shortcuts like handleFirstResult and handleSecondResult above, but from within the
      // stepper
      def firstResult(res: Result[I2]): SimpleCalc = res match {
        case Continue             ⇒ Finished(Continue)
        case b: BackchannelResult ⇒ Finished(b)
        case x                    ⇒ FirstResult(x)
      }
      def secondResult(res: Result[O]): SimpleCalc = res match {
        case Continue            ⇒ Finished(Continue)
        case f: ForwardResult[O] ⇒ Finished(f)
        case x                   ⇒ SecondResult(x)
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
      new OpInstance[I, O] with SimplePublisherHandler[O] {
        val batchSize = 10000
        var missing = 0
        var z = seed
        var completed = false
        def requestMore(n: Int): Int = {
          // we can instantly consume all values, even if there's just one requested,
          // though we probably need to be careful with MaxValue which may lead to
          // overflows easily

          missing = batchSize
          batchSize
        }

        def handle(result: ForwardResult[I]): Result[O] = result match {
          case Emit(i) ⇒
            z = acc(z, i) // FIXME: error handling
            missing -= 1
            maybeRequestMore
          case EmitMany(is) ⇒
            z = is.foldLeft(z)(acc) // FIXME: error handling
            missing -= is.size
            maybeRequestMore
          case Complete ⇒
            if (!completed) {
              completed = true
              Emit(z) ~ Complete
            } else Continue
          case e: Error ⇒ e
        }
        def maybeRequestMore: Result[O] =
          if (missing == 0) {
            missing = batchSize
            RequestMore(batchSize)
          } else Continue
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
                  RequestMore(1)
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
                    RequestMore(n)
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
      new OpInstance[I, O] with SimpleSubscriptionHandler[I, O] {
        val it = iterable.iterator
        override def requestMoreResult(n: Int): Result[O] =
          if (n > 0)
            if (it.hasNext) {
              val res = rec(new VectorBuilder[O], n)
              if (it.hasNext) EmitMany(res)
              else EmitMany(res) ~ Complete
            } else Complete
          else throw new IllegalStateException(s"n = $n is not > 0")

        @tailrec def rec(result: VectorBuilder[O], remaining: Int): Vector[O] =
          if (remaining > 0 && it.hasNext) rec(result += it.next(), remaining - 1)
          else result.result()

        def onNext(i: I): Result[O] = ???
        def onComplete(): Result[O] = ???
        def onError(cause: Throwable): Result[O] = ???
      }
  }
}

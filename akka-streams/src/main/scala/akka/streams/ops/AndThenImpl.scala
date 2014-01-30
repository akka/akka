package akka.streams
package ops

import scala.annotation.tailrec
import rx.async.api.Producer

object AndThenImpl {
  def apply[I1, I2, O](firstI: OpInstance[I1, I2], secondI: OpInstance[I2, O]): OpInstance[I1, O] =
    new OpInstance[I1, O] {
      def handle(result: SimpleResult[I1]): Result[O] = result match {
        case f: ForwardResult[I1] ⇒ handleFirstResult(firstI.handle(f))
        case b: BackchannelResult ⇒ handleSecondResult(secondI.handle(b))
      }

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
        case Continue ⇒ Continue
        case Emit(InternalPublisherTemplate(f)) ⇒
          // TODO: can we fix the types here?
          val rest = f(new SubscriptionResults {
            def requestMore(n: Int): Result[Nothing] = handleSecondResult(RequestMore(n)).asInstanceOf[Result[Nothing]]
          })
          Emit(InternalPublisherFinished(rest).asInstanceOf[O])
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
        // feed backwards
        case SecondResult(b: BackchannelResult) ⇒ firstResult(firstI.handle(b))
        case SecondResult(x)                    ⇒ secondResult(x)
        case FirstResult(f: ForwardResult[_])   ⇒ secondResult(secondI.handle(f))
        case FirstResult(x)                     ⇒ firstResult(x)
      }

      // these are shortcuts like handleFirstResult and handleSecondResult above, but from within the
      // stepper
      def firstResult(res: Result[I2]): SimpleCalc = res match {
        case Continue             ⇒ Finished(Continue)
        case b: BackchannelResult ⇒ Finished(b)
        case x                    ⇒ FirstResult(x)
      }
      def secondResult(res: Result[O]): SimpleCalc = res match {
        case Continue ⇒ Finished(Continue)
        case Emit(InternalPublisherTemplate(f)) ⇒
          // TODO: can we fix the types here?
          val rest = f(new SubscriptionResults {
            def requestMore(n: Int): Result[Nothing] = handleSecondResult(RequestMore(n)).asInstanceOf[Result[Nothing]]
          })
          Finished(Emit(InternalPublisherFinished(rest).asInstanceOf[O]))
        case s @ Subscribe(InternalPublisherFinished(rest)) ⇒
          object Connector extends PublisherResults[Any] with SubscriptionResults {
            def emit(o: Any): Result[Producer[Any]] = handle(Emit(o.asInstanceOf[O]))
            def complete: Result[Producer[Any]] = handle(Complete)
            def error(cause: Throwable): Result[Producer[Any]] = handle(Error(cause))

            def handle(res: ForwardResult[O]): Result[Producer[Any]] = oHandler.handle(res).asInstanceOf[Result[Producer[Any]]]
            def requestMore(n: Int): Result[Nothing] =
              iHandler.handle(RequestMore(n)).asInstanceOf[Result[Nothing]]
          }
          lazy val oHandler = s.handlerFactory(Connector)
          lazy val iHandler = rest(Connector)

          SecondResult(oHandler.initial)
        case f: ForwardResult[O] ⇒ Finished(f)
        case x                   ⇒ SecondResult(x)
      }
    }
}

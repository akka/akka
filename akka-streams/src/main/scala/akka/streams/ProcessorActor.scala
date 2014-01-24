package akka.streams

import rx.async.api.{ Producer, Processor }

object ProcessorActor {
  // TODO: needs settings
  def processor[I, O](operations: Operation[I, O]): Processor[I, O] = ???

  sealed trait RequestMoreResult
  case class RequestMoreFrom(n: Int, what: Producer[_])
  def requestMore(n: Int, operations: Operation[_, _]): Int = operations match {
    case AndThen(first, second)        ⇒ requestMore(requestMore(n, second), first)
    case Map(_)                        ⇒ n
    case Fold(seed, acc /*, batch */ ) ⇒
  }

  sealed trait OnNextResult[+O] {
    def map[O2](f: O ⇒ O2): OnNextResult[O2]
    def flatMap[O2](f: O ⇒ OnNextResult[O2]): OnNextResult[O2]
  }
  case class Emit[O](t: O) extends OnNextResult[O] {
    def map[O2](f: O ⇒ O2): OnNextResult[O2] = Emit(f(t))
    def flatMap[O2](f: O ⇒ OnNextResult[O2]): OnNextResult[O2] = f(t)
  }
  case object Continue extends OnNextResult[Nothing] {
    def map[O2](f: Nothing ⇒ O2): OnNextResult[O2] = this
    def flatMap[O2](f: Nothing ⇒ OnNextResult[O2]): OnNextResult[O2] = this
  }

  /*trait State[I]
  trait StateHolder {
    def init[I](init: I): State[I]
    def apply[I](s: State[I]): I
    def update[I](s: State[I], i: I): Unit
  }

  trait InstantiatedOp[I, O]
  case class PureOp[I, O](operation: Operation[I, O]) extends InstantiatedOp[I, O]
  case class StatefulOp[I, O, Z](operation: StatefulOperation[I, O, Z], state: State[Z]) extends InstantiatedOp[I, O]
  case class AndThenOp[I1, I2, O](op1: InstantiatedOp[I1, I2], op2: InstantiatedOp[I2, O]) extends InstantiatedOp[I1, O]

  def instantiate[I, O](operation: Operation[I, O], stateHolder: StateHolder): InstantiatedOp[I, O] = operation match {
    case s: StatefulOperation[I, O, _] ⇒ StatefulOp(s, stateHolder.init(s.initState))
    case AndThen(first, second)        ⇒ AndThenOp(instantiate(first, stateHolder), instantiate(second, stateHolder))
    case op: Operation[I, O]           ⇒ PureOp(op)
  }

  def onNext[I, O](operations: InstantiatedOp[I, O], stateHolder: StateHolder): I ⇒ OnNextResult[O] = operations match {
    case PureOp(op) ⇒ onNext(op)
    case AndThenOp(first, second) ⇒
      val f0 = onNext(first, stateHolder)
      val s0 = onNext(second, stateHolder)
      i ⇒ f0(i).flatMap(s0)
      case StatefulOp(op, state) ⇒
      val o = onNextStateful(op)

      { i ⇒
        val z = stateHolder(state)
        val (newZ, res) = o(z, i)
        stateHolder(state) = newZ
        res
      }
  }
  def onNextStateful[I, O, Z](operation: StatefulOperation[I, O, Z]): (Z, I) ⇒ (Z, OnNextResult[O]) = operation match {
    case f: Fold[_, _]       ⇒ foldNext(f)
    case f @ FoldUntil(_, _) ⇒ foldUntilNext[I, O, Z](f)
  }

  def foldNext[I, Z](fold: Fold[I, Z]): (Z, I) ⇒ (Z, OnNextResult[Z]) =
    (z, i) ⇒ (fold.acc(z, i), Continue)

  def foldUntilNext[I, O, Z](foldUntil: FoldUntil[I, O, Z]): (Z, I) ⇒ (Z, OnNextResult[O]) =
    (z, i) ⇒ foldUntil.acc(z, i) match {
      case FoldResult.Emit(value, next) ⇒ (next, Emit(value))
      case FoldResult.Continue(state)   ⇒ (state, Continue)
    }*/

  def onNext[I, O](operation: Operation[I, O]): I ⇒ OnNextResult[O] = operation match {
    case AndThen(first, second) ⇒
      val f0 = onNext(first)
      val s0 = onNext(second)
      i ⇒ f0(i).flatMap(s0)

      case Fold(init, acc) ⇒
      var z = init

      { i ⇒
        // but how gets z ever returned at onComplete?
        z = acc(init, i)
        Continue
      }

    case Map(f) ⇒ i ⇒ Emit(f(i))
    case Filter(pred) ⇒ i ⇒ if (pred(i)) Emit(i.asInstanceOf[O]) else Continue
    case Consume(_) ⇒ i ⇒ Emit(i).asInstanceOf[Emit[O]]
    case Identity() ⇒ i ⇒ Emit(i).asInstanceOf[Emit[O]]
  }
}

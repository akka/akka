package akka.streams.ops2

import akka.streams.Operation
import Operation._

object AndThenImpl {
  def implementation[O](downstream: Downstream[O], subscribable: Subscribable, source: Source[O]): SyncSource[O] =
    source match {
      case m: MappedSource[i, O] ⇒
        AndThenImpl.source[i, O](implementation(_: Downstream[i], subscribable, m.source), up ⇒ implementation(up, downstream, subscribable, m.operation))
      case FromIterableSource(s) ⇒ FromIterableSourceImpl(downstream, subscribable, s)
    }

  def implementation[I, O](upstream: Upstream, downstream: Downstream[O], subscribable: Subscribable, op: Operation[I, O]): SyncOperation[I, O] = op match {
    case a: AndThen[I, i2, O] ⇒
      AndThenImpl.operation(implementation(upstream, _: Downstream[i2], subscribable, a.f), implementation(_, downstream, subscribable, a.g))
    case Map(f)              ⇒ MapImpl(upstream, downstream, f)
    case Flatten()           ⇒ FlattenImpl(upstream, downstream, subscribable).asInstanceOf[SyncOperation[I, O]]
    case d: DirectFold[I, O] ⇒ FoldImpl(upstream, downstream, d)
  }

  case class NextToRight[B](right: SyncSink[B, _], element: B) extends ResultImpl[B](right.handleNext(element))
  case class CompleteRight(right: SyncSink[_, _]) extends ResultImpl[Nothing](right.handleComplete())
  case class ErrorToRight[B](right: SyncSink[B, _], cause: Throwable) extends ResultImpl[B](right.handleError(cause))

  case class RequestMoreFromLeft(left: SyncSource[_], n: Int) extends ResultImpl[Nothing](left.handleRequestMore(n))
  case class CancelLeft(left: SyncSource[_]) extends ResultImpl[Nothing](left.handleCancel())

  def source[B, C] //(upstream: Upstream, downstream: Downstream[C]) //
  /*             */ (_leftCons: Downstream[B] ⇒ SyncSource[B], _rightCons: Upstream ⇒ SyncOperation[B, C]): SyncSource[C] =
    new AbstractAndThenImpl[B, C] with SyncSource[C] {
      type Left = SyncSource[B]
      type Right = SyncOperation[B, C]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      def handleRequestMore(n: Int): Result[C] = handleRightResult(right.handleRequestMore(n))
      def handleCancel(): Result[C] = handleRightResult(right.handleCancel())
    }

  def operation[A, B, C] //(upstream: Upstream, downstream: Downstream[C]) //
  /*             */ (_leftCons: Downstream[B] ⇒ SyncOperation[A, B], _rightCons: Upstream ⇒ SyncOperation[B, C]): SyncOperation[A, C] =
    new AbstractAndThenImpl[B, C] with SyncOperation[A, C] {
      type Left = SyncOperation[A, B]
      type Right = SyncOperation[B, C]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      def handleRequestMore(n: Int): Result[C] = handleRightResult(right.handleRequestMore(n))
      def handleCancel(): Result[C] = handleRightResult(right.handleCancel())

      def handleNext(element: A): Result[C] = handleLeftResult(left.handleNext(element))
      def handleComplete(): Result[C] = handleLeftResult(left.handleComplete())
      def handleError(cause: Throwable): Result[C] = handleLeftResult(left.handleError(cause))

    }

  abstract class AbstractAndThenImpl[B, C] {
    type Left <: SyncSource[B]
    type Right <: SyncSink[B, C]

    def leftCons: Downstream[B] ⇒ Left
    def rightCons: Upstream ⇒ Right

    val innerDownstream = new Downstream[B] {
      val next: B ⇒ Result[B] = NextToRight(right, _)
      lazy val complete: Result[Nothing] = CompleteRight(right)
      val error: Throwable ⇒ Result[B] = ErrorToRight(right, _)
    }
    val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Result[Nothing] = RequestMoreFromLeft(left, _)
      val cancel: Result[Nothing] = CancelLeft(left)
    }
    val left: Left = leftCons(innerDownstream)
    val right: Right = rightCons(innerUpstream)

    // TODO: add shortcuts for at least one direction (or one step)
    def handleLeftResult(result: Result[B]): Result[C] = result match {
      //case NextToRight(_, element) ⇒ right.handleNext(element)
      //case CompleteRight(_)       ⇒ right.handleComplete()
      //case ErrorToRight(_, cause) ⇒ right.handleError(cause)
      case x ⇒ x.asInstanceOf[Result[C]]
    }
    def handleRightResult(result: Result[C]): Result[C] = result match {
      //case f: Backward ⇒ f.run().asInstanceOf[Result[C]]
      //case RequestMoreFromLeft(_, n) ⇒ left.handleRequestMore(n).asInstanceOf[Result[C]]
      //case CancelLeft(_)             ⇒ left.handleCancel().asInstanceOf[Result[C]]
      case x ⇒ x.asInstanceOf[Result[C]]
    }
  }
}

class ResultImpl[O](body: ⇒ Result[_]) extends Step[O] {
  def run(): Result[_] = body
}

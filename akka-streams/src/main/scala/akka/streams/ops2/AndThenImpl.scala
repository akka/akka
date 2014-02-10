package akka.streams.ops2

import akka.streams.Operation
import Operation._

object AndThenImpl {
  def implementation[A](subscribable: Subscribable, p: Pipeline[A]): SyncRunnable = {
    pipeline(implementation[A](_: Downstream[A], subscribable, p.source), implementation[A](_: Upstream, subscribable, p.sink))
  }
  def implementation[I](upstream: Upstream, subscribable: Subscribable, sink: Sink[I]): SyncSink[I] =
    sink match {
      case Foreach(f)          ⇒ ForeachImpl(upstream, f)
      case FromConsumerSink(s) ⇒ FromConsumerSinkImpl(upstream, subscribable, s)
    }
  def implementation[O](downstream: Downstream[O], subscribable: Subscribable, source: Source[O]): SyncSource =
    source match {
      case m: MappedSource[i, O] ⇒
        AndThenImpl.source[i, O](implementation(_: Downstream[i], subscribable, m.source), up ⇒ implementation(up, downstream, subscribable, m.operation))
      case FromIterableSource(s)    ⇒ FromIterableSourceImpl(downstream, subscribable, s)
      case f: FromProducerSource[_] ⇒ FromProducerSourceImpl(downstream, subscribable, f)
    }

  def implementation[I, O](upstream: Upstream, downstream: Downstream[O], subscribable: Subscribable, op: Operation[I, O]): SyncOperation[I] = op match {
    case a: AndThen[I, i2, O] ⇒
      AndThenImpl.operation(implementation(upstream, _: Downstream[i2], subscribable, a.f), implementation(_, downstream, subscribable, a.g))
    case Map(f)              ⇒ MapImpl(upstream, downstream, f)
    case i: Identity[O]      ⇒ IdentityImpl(upstream, downstream).asInstanceOf[SyncOperation[I]]
    case Flatten()           ⇒ FlattenImpl(upstream, downstream, subscribable).asInstanceOf[SyncOperation[I]]
    case d: DirectFold[I, O] ⇒ FoldImpl(upstream, downstream, d)
  }

  case class NextToRight[B](right: SyncSink[B], element: B) extends ResultImpl(right.handleNext(element))
  case class CompleteRight(right: SyncSink[_]) extends ResultImpl(right.handleComplete())
  case class ErrorToRight[B](right: SyncSink[B], cause: Throwable) extends ResultImpl(right.handleError(cause))

  case class RequestMoreFromLeft(left: SyncSource, n: Int) extends ResultImpl(left.handleRequestMore(n))
  case class CancelLeft(left: SyncSource) extends ResultImpl(left.handleCancel())

  def pipeline[B, C](_leftCons: Downstream[B] ⇒ SyncSource, _rightCons: Upstream ⇒ SyncSink[B]): SyncRunnable =
    new AbstractAndThenImpl[B, B] with SyncRunnable {
      type Left = SyncSource
      type Right = SyncSink[B]

      def leftCons = _leftCons
      def rightCons = _rightCons

      override def start(): Result = right.start()
    }

  def source[B, C] //(upstream: Upstream, downstream: Downstream[C]) //
  /*             */ (_leftCons: Downstream[B] ⇒ SyncSource, _rightCons: Upstream ⇒ SyncOperation[B]): SyncSource =
    new AbstractAndThenImpl[B, C] with SyncSource {
      type Left = SyncSource
      type Right = SyncOperation[B]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      def handleRequestMore(n: Int): Result = handleRightResult(right.handleRequestMore(n))
      def handleCancel(): Result = handleRightResult(right.handleCancel())
    }

  def operation[A, B, C] //(upstream: Upstream, downstream: Downstream[C]) //
  /*             */ (_leftCons: Downstream[B] ⇒ SyncOperation[A], _rightCons: Upstream ⇒ SyncOperation[B]): SyncOperation[A] =
    new AbstractAndThenImpl[B, C] with SyncOperation[A] {
      type Left = SyncOperation[A]
      type Right = SyncOperation[B]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      def handleRequestMore(n: Int): Result = handleRightResult(right.handleRequestMore(n))
      def handleCancel(): Result = handleRightResult(right.handleCancel())

      def handleNext(element: A): Result = handleLeftResult(left.handleNext(element))
      def handleComplete(): Result = handleLeftResult(left.handleComplete())
      def handleError(cause: Throwable): Result = handleLeftResult(left.handleError(cause))

      override def start(): Result = right.start()
    }

  abstract class AbstractAndThenImpl[B, C] {
    type Left <: SyncSource
    type Right <: SyncSink[B]

    def leftCons: Downstream[B] ⇒ Left
    def rightCons: Upstream ⇒ Right

    lazy val innerDownstream = new Downstream[B] {
      val next: B ⇒ Result = NextToRight(right, _)
      lazy val complete: Result = CompleteRight(right)
      val error: Throwable ⇒ Result = ErrorToRight(right, _)
    }
    lazy val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Result = RequestMoreFromLeft(left, _)
      val cancel: Result = CancelLeft(left)
    }
    lazy val left: Left = leftCons(innerDownstream)
    lazy val right: Right = rightCons(innerUpstream)

    // TODO: add shortcuts for at least one direction (or one step)
    def handleLeftResult(result: Result): Result = result match {
      //case NextToRight(_, element) ⇒ right.handleNext(element)
      //case CompleteRight(_)       ⇒ right.handleComplete()
      //case ErrorToRight(_, cause) ⇒ right.handleError(cause)
      case x ⇒ x.asInstanceOf[Result]
    }
    def handleRightResult(result: Result): Result = result match {
      //case f: Backward ⇒ f.run().asInstanceOf[Result]
      //case RequestMoreFromLeft(_, n) ⇒ left.handleRequestMore(n).asInstanceOf[Result]
      //case CancelLeft(_)             ⇒ left.handleCancel().asInstanceOf[Result]
      case x ⇒ x.asInstanceOf[Result]
    }
  }
}

class ResultImpl(body: ⇒ Result) extends Step {
  def run(): Result = body
}

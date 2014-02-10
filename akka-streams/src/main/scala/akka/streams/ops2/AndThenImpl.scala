package akka.streams.ops2

import akka.streams.Operation
import Operation._

object AndThenImpl {
  def implementation[A](subscribable: ContextEffects, p: Pipeline[A]): SyncRunnable = {
    pipeline(implementation[A](_: Downstream[A], subscribable, p.source), implementation[A](_: Upstream, subscribable, p.sink))
  }
  def implementation[I](upstream: Upstream, subscribable: ContextEffects, sink: Sink[I]): SyncSink[I] =
    sink match {
      case Foreach(f)          ⇒ ForeachImpl(upstream, f)
      case FromConsumerSink(s) ⇒ FromConsumerSinkImpl(upstream, subscribable, s)
    }
  def implementation[O](downstream: Downstream[O], subscribable: ContextEffects, source: Source[O]): SyncSource =
    source match {
      case m: MappedSource[i, O] ⇒
        AndThenImpl.source[i, O](implementation(_: Downstream[i], subscribable, m.source), up ⇒ implementation(up, downstream, subscribable, m.operation))
      case FromIterableSource(s)    ⇒ FromIterableSourceImpl(downstream, subscribable, s)
      case f: FromProducerSource[_] ⇒ FromProducerSourceImpl(downstream, subscribable, f)
    }

  def implementation[I, O](upstream: Upstream, downstream: Downstream[O], subscribable: ContextEffects, op: Operation[I, O]): SyncOperation[I] = op match {
    case a: AndThen[I, i2, O] ⇒
      AndThenImpl.operation(implementation(upstream, _: Downstream[i2], subscribable, a.f), implementation(_, downstream, subscribable, a.g))
    case Map(f)              ⇒ MapImpl(upstream, downstream, f)
    case i: Identity[O]      ⇒ IdentityImpl(upstream, downstream).asInstanceOf[SyncOperation[I]]
    case Flatten()           ⇒ FlattenImpl(upstream, downstream, subscribable).asInstanceOf[SyncOperation[I]]
    case d: DirectFold[I, O] ⇒ FoldImpl(upstream, downstream, d)
  }

  case class NextToRight[B](right: SyncSink[B], element: B) extends EffectImpl(right.handleNext(element))
  case class CompleteRight(right: SyncSink[_]) extends EffectImpl(right.handleComplete())
  case class ErrorToRight[B](right: SyncSink[B], cause: Throwable) extends EffectImpl(right.handleError(cause))

  case class RequestMoreFromLeft(left: SyncSource, n: Int) extends EffectImpl(left.handleRequestMore(n))
  case class CancelLeft(left: SyncSource) extends EffectImpl(left.handleCancel())

  def pipeline[B, C](_leftCons: Downstream[B] ⇒ SyncSource, _rightCons: Upstream ⇒ SyncSink[B]): SyncRunnable =
    new AbstractAndThenImpl[B, B] with SyncRunnable {
      type Left = SyncSource
      type Right = SyncSink[B]

      def leftCons = _leftCons
      def rightCons = _rightCons

      override def start(): Effect = right.start()
    }

  def source[B, C] //(upstream: Upstream, downstream: Downstream[C]) //
  /*             */ (_leftCons: Downstream[B] ⇒ SyncSource, _rightCons: Upstream ⇒ SyncOperation[B]): SyncSource =
    new AbstractAndThenImpl[B, C] with SyncSource {
      type Left = SyncSource
      type Right = SyncOperation[B]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      def handleRequestMore(n: Int): Effect = handleRightResult(right.handleRequestMore(n))
      def handleCancel(): Effect = handleRightResult(right.handleCancel())
    }

  def operation[A, B, C] //(upstream: Upstream, downstream: Downstream[C]) //
  /*             */ (_leftCons: Downstream[B] ⇒ SyncOperation[A], _rightCons: Upstream ⇒ SyncOperation[B]): SyncOperation[A] =
    new AbstractAndThenImpl[B, C] with SyncOperation[A] {
      type Left = SyncOperation[A]
      type Right = SyncOperation[B]

      def leftCons: Downstream[B] ⇒ Left = _leftCons
      def rightCons: Upstream ⇒ Right = _rightCons

      def handleRequestMore(n: Int): Effect = handleRightResult(right.handleRequestMore(n))
      def handleCancel(): Effect = handleRightResult(right.handleCancel())

      def handleNext(element: A): Effect = handleLeftResult(left.handleNext(element))
      def handleComplete(): Effect = handleLeftResult(left.handleComplete())
      def handleError(cause: Throwable): Effect = handleLeftResult(left.handleError(cause))

      override def start(): Effect = right.start()
    }

  abstract class AbstractAndThenImpl[B, C] {
    type Left <: SyncSource
    type Right <: SyncSink[B]

    def leftCons: Downstream[B] ⇒ Left
    def rightCons: Upstream ⇒ Right

    lazy val innerDownstream = new Downstream[B] {
      val next: B ⇒ Effect = NextToRight(right, _)
      lazy val complete: Effect = CompleteRight(right)
      val error: Throwable ⇒ Effect = ErrorToRight(right, _)
    }
    lazy val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Effect = RequestMoreFromLeft(left, _)
      val cancel: Effect = CancelLeft(left)
    }
    lazy val left: Left = leftCons(innerDownstream)
    lazy val right: Right = rightCons(innerUpstream)

    // TODO: add shortcuts for at least one direction (or one step)
    def handleLeftResult(result: Effect): Effect = result match {
      //case NextToRight(_, element) ⇒ right.handleNext(element)
      //case CompleteRight(_)       ⇒ right.handleComplete()
      //case ErrorToRight(_, cause) ⇒ right.handleError(cause)
      case x ⇒ x
    }
    def handleRightResult(result: Effect): Effect = result match {
      //case f: Backward ⇒ f.run()
      //case RequestMoreFromLeft(_, n) ⇒ left.handleRequestMore(n)
      //case CancelLeft(_)             ⇒ left.handleCancel()
      case x ⇒ x
    }
  }
}

class EffectImpl(body: ⇒ Effect) extends SingleStep {
  def runOne(): Effect = body
}

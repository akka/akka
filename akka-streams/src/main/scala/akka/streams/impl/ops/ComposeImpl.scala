package akka.streams
package impl
package ops

object ComposeImpl {
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
      val next: B ⇒ Effect = BasicEffects.HandleNextInSink(right, _)
      lazy val complete: Effect = BasicEffects.CompleteSink(right)
      val error: Throwable ⇒ Effect = BasicEffects.HandleErrorInSink(right, _)
    }
    lazy val innerUpstream = new Upstream {
      val requestMore: Int ⇒ Effect = BasicEffects.RequestMoreFromSource(left, _)
      val cancel: Effect = BasicEffects.CancelSource(left)
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
